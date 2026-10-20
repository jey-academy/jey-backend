package com.jey.dongtanpass.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

import com.jey.core.audit.api.AuditEntry;
import com.jey.core.audit.api.AuditLog;
import com.jey.core.audit.api.DeniedAttempts;
import com.jey.core.auth.api.AccountRateLimiter;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.CurrentActor;
import com.jey.core.auth.api.RateLimit;
import com.jey.core.member.api.MemberDirectory;
import com.jey.core.member.api.StudentInfo;
import com.jey.core.shared.Actor;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.EventMetadata;
import com.jey.core.shared.UniqueKeys;
import com.jey.dongtanpass.api.PassRedeemed;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassRepository;
import com.jey.dongtanpass.domain.Redemption;
import com.jey.dongtanpass.domain.RedemptionRepository;
import com.jey.dongtanpass.domain.StudentNameKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 데스크가 등록한 학생에게 패스를 쓴다(ADR-0033). 한 트랜잭션에서 확인하고, 패스를 사용됨으로 바꾸고, 사용 기록을 저장하고,
 * {@code dongtanpass.pass-redeemed} 이벤트를 낸다(감사 로그는 이 이벤트로 남는다). 금액과 청구는 다루지 않는다.
 *
 * <p>확인 순서가 오류를 정한다: 받는 지점(403) → 패스(404, 409) → 할인 켜짐(409) → 학생(404) → 학생의 지점(409)
 * → 신규(409) → 이름 → 학부모 번호(409). 미리 확인(holder-check)을 거쳤어도 이름과 번호를 다시 본다.
 *
 * <p>이름·번호가 패스와 맞는지를 오류 코드로 알려 주므로, 패스 ID를 바꿔 가며 패스 주인을 훑지 못하게 계정마다 센다
 * (맞든 틀리든, 패스를 찾기 전에. 미리 확인의 한도와는 따로). 처음 막힌 것은 감사 로그에 남긴다.
 *
 * <p>같은 패스를 동시에 쓰거나 취소·링크 새로 만들기와 겹치면 패스의 버전이, 같은 학생을 동시에 쓰면 사용 기록의 유니크 키가 막는다
 * (유니크 키는 어느 경우든 마지막 방어선이다).
 * 진 쪽의 예외는 트랜잭션 밖에서 상태에 맞는 409로 바꾼다. 패스를 읽는 법과 저장 순서는 {@link RedeemLocking}이 정한다
 * (운영은 {@link OptimisticRedeemLocking}).
 * 같은 학생으로 셋 이상이 동시에 오고 먼저 넣은 요청이 롤백되면, 기다리던 요청끼리 드물게 잠금 실패(데드락)가 나 500으로 끝날 수 있다.
 * 5xx는 멱등 응답으로 보관하지 않으므로 다시 보내면 맞는 답(성공 또는 신규 아님)이 나온다.
 */
@Service
@EnableConfigurationProperties({ RedeemProperties.class, RedeemLimitProperties.class })
public class PassRedeemService {

	// 마이그레이션에 적은 제약 이름. 저장이 어느 제약에 걸렸는지 이것으로 가린다.
	private static final String PASS_UNIQUE_KEY = "uk_dongtanpass_redemption_pass";

	private static final String STUDENT_UNIQUE_KEY = "uk_dongtanpass_redemption_student";

	private static final Logger log = LoggerFactory.getLogger(PassRedeemService.class);

	private final PassRepository passes;

	private final RedemptionRepository redemptions;

	private final RedeemLocking locking;

	private final PassBenefits passBenefits;

	private final MemberDirectory members;

	private final TransactionTemplate transaction;

	private final ApplicationEventPublisher events;

	private final CurrentActor currentActor;

	private final RedeemProperties properties;

	private final AccountRateLimiter limiter;

	private final RateLimit redeemLimit;

	private final AuditLog auditLog;

	private final Clock clock;

	PassRedeemService(PassRepository passes, RedemptionRepository redemptions, RedeemLocking locking,
			PassBenefits passBenefits,
			MemberDirectory members, TransactionTemplate transaction, ApplicationEventPublisher events,
			CurrentActor currentActor, RedeemProperties properties, AccountRateLimiter limiter,
			RedeemLimitProperties redeemLimit, AuditLog auditLog, Clock clock) {
		this.passes = passes;
		this.redemptions = redemptions;
		this.locking = locking;
		this.passBenefits = passBenefits;
		this.members = members;
		this.transaction = transaction;
		this.events = events;
		this.currentActor = currentActor;
		this.properties = properties;
		this.limiter = limiter;
		this.redeemLimit = redeemLimit.limit();
		this.auditLog = auditLog;
		this.clock = clock;
	}

	/**
	 * @throws BusinessException 패스를 받지 않는 지점의 직원(403), 없는 패스·학생(404), 쓸 수 없는 패스, 꺼진 할인,
	 * 받는 지점의 학생이 아님, 신규가 아님, 이름이나 학부모 번호가 패스와 다름, 동시에 바뀐 패스(409)
	 * @throws com.jey.core.shared.RateLimitedException 그 계정의 사용 처리가 한도를 넘었을 때(429)
	 */
	public RedeemedPass redeem(long passId, long studentId, AuthenticatedUser user) {
		PassBenefit benefit = passBenefits.current();
		DeskChecks.requireProvidingCampus(benefit, user);
		// 맞든 틀리든, 패스를 찾기 전에 센다. 트랜잭션 밖이다(막힌 기록은 트랜잭션이 끝난 뒤에 적는다).
		limiter.acquire(redeemLimit, new Actor.User(user.id()), DongtanpassErrorCode.REDEEM_LIMITED,
				denial -> DeniedAttempts.record(auditLog, () -> AuditEntry.of(DongtanpassAuditAction.REDEEM_LIMITED),
						denial));
		try {
			return locking.guard(passId,
					() -> transaction.execute(status -> redeemInTransaction(passId, studentId, benefit)));
		}
		catch (DataIntegrityViolationException ex) {
			// 같은 패스(또는 같은 학생)를 먼저 쓴 요청이 있다. 다른 제약에 걸린 것은 그대로 던진다(서버 오류로 드러나야 한다).
			if (UniqueKeys.isViolationOf(ex, PASS_UNIQUE_KEY)) {
				throw new BusinessException(DongtanpassErrorCode.PASS_ALREADY_REDEEMED, null, ex);
			}
			if (UniqueKeys.isViolationOf(ex, STUDENT_UNIQUE_KEY)) {
				throw new BusinessException(DongtanpassErrorCode.STUDENT_NOT_NEW, null, ex);
			}
			throw ex;
		}
		catch (OptimisticLockingFailureException ex) {
			throw lostRace(passId, ex);
		}
	}

	private RedeemedPass redeemInTransaction(long passId, long studentId, PassBenefit benefit) {
		Instant now = clock.instant();
		Pass pass = locking.load(passId)
			.orElseThrow(() -> new BusinessException(DongtanpassErrorCode.PASS_NOT_FOUND));
		DeskChecks.requirePartnerIssued(pass);
		DeskChecks.requireUsable(pass, todayAt(now));
		if (!benefit.discountActive()) {
			throw new BusinessException(DongtanpassErrorCode.DISCOUNT_INACTIVE);
		}
		StudentInfo student = members.findStudent(studentId)
			.orElseThrow(() -> new BusinessException(DongtanpassErrorCode.STUDENT_NOT_FOUND));
		// 직원은 받는 지점 소속임을 이미 봤다. 관리자도 다른 지점 학생에게는 쓸 수 없다.
		if (student.campusId() != benefit.campusId()) {
			throw new BusinessException(DongtanpassErrorCode.STUDENT_NOT_AT_PROVIDING_CAMPUS);
		}
		if (!student.enrolled() || !properties.isNew(student.createdAt(), now)
				|| redemptions.existsByStudentId(studentId)) {
			throw new BusinessException(DongtanpassErrorCode.STUDENT_NOT_NEW);
		}
		if (!StudentNameKey.of(pass.getStudentName()).matches(student.name())) {
			throw new BusinessException(DongtanpassErrorCode.HOLDER_NAME_MISMATCH);
		}
		if (!members.hasGuardianWithPhoneIndex(studentId, pass.getGuardianPhoneIndex())) {
			throw new BusinessException(DongtanpassErrorCode.HOLDER_PHONE_MISMATCH);
		}
		// 저장 순서(잠금 순서)는 방식이 정한다. 같은 패스·학생을 먼저 쓴 요청이 있으면 여기서 버전 충돌이나 유니크 키에 걸린다.
		Redemption saved = locking.store(pass, pass.redeem(studentId, benefit.campusId(), now));
		events.publishEvent(new PassRedeemed(EventMetadata.create(PassRedeemed.TYPE, PassRedeemed.VERSION,
				benefit.campusId(), currentActor.get(), clock), passId, studentId, pass.getPartnerId()));
		return new RedeemedPass(saved.getId(), passId, studentId, now);
	}

	// 패스를 같이 고친 요청(취소, 링크 새로 만들기, 다른 사용 처리)에 졌다. 지금 상태를 다시 읽어 그 사정으로 답한다.
	// 아직 쓸 수 있으면(링크 새로 만들기에 진 경우) 다시 시도하면 된다.
	private BusinessException lostRace(long passId, OptimisticLockingFailureException ex) {
		Pass current;
		try {
			current = passes.findById(passId).orElseThrow(() -> ex);
		}
		catch (RuntimeException readFailure) {
			// 다시 읽지 못했다(DB 오류 등). 그 오류로 끝내되 원래의 충돌도 함께 남긴다.
			if (readFailure != ex) {
				readFailure.addSuppressed(ex);
			}
			throw readFailure;
		}
		try {
			DeskChecks.requireUsable(current, todayAt(clock.instant()));
		}
		catch (BusinessException unusable) {
			return unusable;
		}
		log.info("패스 사용 처리가 같은 패스를 고친 다른 요청에 졌다: passId={}", passId);
		return new BusinessException(DongtanpassErrorCode.PASS_CHANGED_CONCURRENTLY, null, ex);
	}

	private LocalDate todayAt(Instant now) {
		return LocalDate.ofInstant(now, clock.getZone());
	}

}
