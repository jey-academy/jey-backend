package com.jey.dongtanpass.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

import com.jey.core.audit.api.AuditEntry;
import com.jey.core.audit.api.AuditLog;
import com.jey.core.auth.api.CurrentActor;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.EventMetadata;
import com.jey.core.shared.PhoneProtector;
import com.jey.core.shared.UniqueKeys;
import com.jey.dongtanpass.api.PassIssued;
import com.jey.dongtanpass.api.PassRefs;
import com.jey.dongtanpass.domain.Consent;
import com.jey.dongtanpass.domain.ConsentRepository;
import com.jey.dongtanpass.domain.LinkToken;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassRepository;
import com.jey.dongtanpass.domain.PassStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 연계 학원이 패스를 발급하고, 학부모 링크를 새로 만들고, 쓰지 않은 패스를 취소한다(ADR-0001, ADR-0035).
 *
 * <p>학부모 번호는 전체 번호 색인만 저장한다. 링크 토큰은 해시만 저장하고 원문은 돌려주는 값에만 싣는다.
 * 발급은 이벤트({@code dongtanpass.pass-issued})로, 링크 새로 만들기와 취소는 직접 감사 로그에 남긴다.
 * 다른 학원의 패스는 없는 것과 같다(404).
 */
@Service
public class PassService {

	/** 패스 번호가 겹칠 때 발급을 몇 번까지 해 보는지. 32글자 여섯 자리라 실제로는 거의 겹치지 않는다. */
	static final int MAX_PASS_NO_ATTEMPTS = 3;

	// 마이그레이션에 적은 제약 이름. 저장이 어느 제약에 걸렸는지 이것으로 가린다.
	private static final String PASS_NO_UNIQUE_KEY = "uk_dongtanpass_pass_no";

	private static final Logger log = LoggerFactory.getLogger(PassService.class);

	private final PassRepository passes;

	private final ConsentRepository consents;

	private final TransactionTemplate transaction;

	private final PhoneProtector phoneProtector;

	private final ApplicationEventPublisher events;

	private final CurrentActor currentActor;

	private final Clock clock;

	private final PassNumbers passNumbers;

	private final AuditLog auditLog;

	private final SecureRandom random = new SecureRandom();

	PassService(PassRepository passes, ConsentRepository consents, TransactionTemplate transaction,
			PhoneProtector phoneProtector, ApplicationEventPublisher events, CurrentActor currentActor, Clock clock,
			PassNumbers passNumbers, AuditLog auditLog) {
		this.passes = passes;
		this.consents = consents;
		this.transaction = transaction;
		this.phoneProtector = phoneProtector;
		this.events = events;
		this.currentActor = currentActor;
		this.clock = clock;
		this.passNumbers = passNumbers;
		this.auditLog = auditLog;
	}

	/**
	 * 패스를 발급한다. 동의 기록과 함께 한 트랜잭션에서 저장하고 이벤트를 낸다.
	 * 패스 번호가 이미 있는 번호와 겹치면 트랜잭션을 통째로 다시 한다({@link #MAX_PASS_NO_ATTEMPTS}번까지. 그 뒤에는 저장 예외를 그대로 던진다).
	 *
	 * @throws BusinessException 동의를 확인하지 않았거나 고지 버전이 다를 때, 결제일이 오늘보다 뒤일 때,
	 * 결제 금액이 0원이거나 1원 단위가 아닐 때
	 */
	public IssuedPass issue(long partnerId, NewPass input) {
		validate(input);
		String phoneIndex = phoneProtector.fullIndex(input.guardianPhone());
		for (int attempt = 1;; attempt++) {
			try {
				return transaction.execute(status -> save(partnerId, input, phoneIndex));
			}
			catch (DataIntegrityViolationException ex) {
				// 다른 제약에 걸린 것은 그대로 던진다(서버 오류로 드러나야 한다).
				if (!UniqueKeys.isViolationOf(ex, PASS_NO_UNIQUE_KEY)) {
					throw ex;
				}
				if (attempt == MAX_PASS_NO_ATTEMPTS) {
					// 일반 저장 오류와 가려 볼 수 있게 남긴다. 계속 겹치면 번호 공간이나 생성기를 봐야 한다.
					log.error("패스 번호가 {}번 연속 겹쳐 발급을 포기한다: partnerId={}", attempt, partnerId);
					throw ex;
				}
				log.warn("패스 번호가 이미 있는 번호와 겹쳐 다시 발급한다: attempt={}, partnerId={}", attempt, partnerId);
			}
		}
	}

	/**
	 * 학부모 링크를 새로 만든다. 예전 링크는 바로 쓸 수 없다. 기간이 지난 패스도 된다.
	 *
	 * @throws BusinessException 이 학원의 패스가 아닐 때(404), 사용됐거나 취소된 패스일 때(409)
	 */
	public RenewedLink renewLink(long partnerId, long passId) {
		return transaction.execute(status -> {
			Pass pass = lockedPass(partnerId, passId);
			LinkToken link = LinkToken.generate(random);
			changeState(() -> pass.renewLink(link.hash()));
			auditLog.record(AuditEntry.of(DongtanpassAuditAction.PASS_LINK_RENEWED).target(PassRefs.pass(passId)));
			return new RenewedLink(passId, link.raw());
		});
	}

	/**
	 * 쓰지 않은 패스를 취소한다. 기간이 지난 패스도 된다. 결제 환불은 연계 학원이 따로 한다.
	 *
	 * @throws BusinessException 이 학원의 패스가 아닐 때(404), 사용됐거나 이미 취소된 패스일 때(409)
	 */
	public CanceledPass cancel(long partnerId, long passId) {
		return transaction.execute(status -> {
			Pass pass = lockedPass(partnerId, passId);
			changeState(pass::cancel);
			auditLog.record(AuditEntry.of(DongtanpassAuditAction.PASS_CANCELED).target(PassRefs.pass(passId)));
			return new CanceledPass(passId, pass.stateOn(LocalDate.now(clock)));
		});
	}

	private Pass lockedPass(long partnerId, long passId) {
		return passes.findByIdAndPartnerIdForUpdate(passId, partnerId)
			.orElseThrow(() -> new BusinessException(DongtanpassErrorCode.PASS_NOT_FOUND));
	}

	// 지금 상태에서 할 수 없는 일이면 상태에 맞는 오류로 바꾼다.
	private static void changeState(Runnable change) {
		try {
			change.run();
		}
		catch (PassStatusException ex) {
			DongtanpassErrorCode code = switch (ex.status()) {
				case CANCELED -> DongtanpassErrorCode.PASS_CANCELED;
				case REDEEMED -> DongtanpassErrorCode.PASS_ALREADY_REDEEMED;
				// 발급 상태에서는 상태 예외가 나지 않는다. 났다면 코드의 버그다.
				case ISSUED -> throw new IllegalStateException("발급 상태인데 상태 예외가 났다", ex);
			};
			throw new BusinessException(code, null, ex);
		}
	}

	private IssuedPass save(long partnerId, NewPass input, String phoneIndex) {
		Instant now = clock.instant();
		LinkToken link = LinkToken.generate(random);
		// 번호가 겹치면 여기서 유니크 제약에 걸린다(커밋 때까지 미루지 않는다).
		Pass pass = passes.saveAndFlush(Pass.issue(partnerId, passNumbers.next(), link.hash(),
				input.studentName(), phoneIndex, now, clock.getZone(), input.paidOn(), input.paidAmount(),
				input.paymentMethod()));
		consents.save(Consent.thirdPartyProvision(pass.getId(), input.noticeVersion(), now));
		// 같은 트랜잭션에서 낸다. 발급이 커밋돼야 이벤트도 나간다.
		events.publishEvent(new PassIssued(
				EventMetadata.create(PassIssued.TYPE, PassIssued.VERSION, null, currentActor.get(), clock),
				pass.getId(), partnerId));
		return new IssuedPass(pass.getId(), pass.getPassNo(), link.raw(), now, pass.getExpiresOn());
	}

	private void validate(NewPass input) {
		if (!input.consentConfirmed()) {
			throw new BusinessException(DongtanpassErrorCode.CONSENT_NOT_CONFIRMED);
		}
		if (!ConsentNotice.isCurrent(input.noticeVersion())) {
			throw new BusinessException(DongtanpassErrorCode.NOTICE_VERSION_MISMATCH);
		}
		if (input.paidOn().isAfter(LocalDate.now(clock))) {
			throw new BusinessException(DongtanpassErrorCode.PAID_ON_IN_FUTURE);
		}
		// 반 수강료와 같은 규칙이다.
		if (input.paidAmount().isZero() || !input.paidAmount().isWholeWon()) {
			throw new BusinessException(DongtanpassErrorCode.INVALID_PAID_AMOUNT);
		}
	}

}
