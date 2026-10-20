package com.jey.dongtanpass.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.jey.core.audit.api.AuditEntry;
import com.jey.core.audit.api.AuditLog;
import com.jey.core.audit.api.DeniedAttempts;
import com.jey.core.auth.api.AccountRateLimiter;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.RateLimit;
import com.jey.core.campus.api.CampusCatalog;
import com.jey.core.shared.Actor;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.Money;
import com.jey.core.shared.PhoneNumber;
import com.jey.core.shared.PhoneProtector;
import com.jey.dongtanpass.domain.MaskedName;
import com.jey.dongtanpass.domain.PartnerDiscount;
import com.jey.dongtanpass.domain.PartnerRedemptionRepository;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassDirection;
import com.jey.dongtanpass.domain.PassRepository;
import com.jey.dongtanpass.domain.StudentNameKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 연계 학원이 JEY 발급 패스를 확인하고, 본인을 확인하고, 받을 금액을 미리 본다(사용 처리의 앞 단계들). 연계 학원 계정만 부른다(컨트롤러가 막고, 꺼진 학원은 인터셉터가 403).
 *
 * <p>확인 순서가 오류를 정한다: (번호는 계정별 한도) → 패스(404, QR은 서명 400) → 연계 학원 발급분(409 {@code PASS_FOR_CAMPUS_USE})
 * → 이 학원에서 같은 학생이 이미 할인받음(409 {@code STUDENT_ALREADY_USED_AT_PARTNER}) → (QR이면 여기서 토큰을 쓴 것으로 적는다) → 상태(409).
 * 앞의 둘은 그 패스를 다른 곳에서 그대로 쓸 수 있으므로 토큰을 쓰기 전에 거부한다. 상태가 틀린 패스는 데스크와 같이 토큰을 쓴 뒤 거부한다
 * (어디서도 쓸 수 없다). 횟수 한도는 데스크와 같은 이름·값이다(계정마다 센다).
 */
@Service
public class PartnerPassService {

	private static final Logger log = LoggerFactory.getLogger(PartnerPassService.class);

	private final PassRepository passes;

	private final PartnerRedemptionRepository partnerRedemptions;

	private final PassQrCodes passQrCodes;

	private final UsedQrTokens usedQrTokens;

	private final AccountRateLimiter limiter;

	private final RateLimit passNoLimit;

	private final RateLimit holderCheckLimit;

	private final PhoneProtector phoneProtector;

	private final AuditLog auditLog;

	private final CampusCatalog catalog;

	private final Clock clock;

	PartnerPassService(PassRepository passes, PartnerRedemptionRepository partnerRedemptions, PassQrCodes passQrCodes,
			UsedQrTokens usedQrTokens, AccountRateLimiter limiter, PassNoVerifyProperties passNoVerify,
			HolderCheckProperties holderCheck, PhoneProtector phoneProtector, AuditLog auditLog, CampusCatalog catalog,
			Clock clock) {
		this.passes = passes;
		this.partnerRedemptions = partnerRedemptions;
		this.passQrCodes = passQrCodes;
		this.usedQrTokens = usedQrTokens;
		this.limiter = limiter;
		this.passNoLimit = passNoVerify.limit();
		this.holderCheckLimit = holderCheck.limit();
		this.phoneProtector = phoneProtector;
		this.auditLog = auditLog;
		this.catalog = catalog;
		this.clock = clock;
	}

	/**
	 * @throws BusinessException 무효이거나 이미 쓴 토큰(400), 연계 학원 발급 패스·이 학원에서 이미 할인받은 학생·쓸 수 없는 패스(409)
	 */
	public PartnerVerifiedPassView verifyQr(String qrToken, AuthenticatedUser user) {
		long partnerId = partnerIdOf(user);
		Instant now = clock.instant();
		VerifiedQr qr = passQrCodes.verify(qrToken, now)
			.orElseThrow(() -> new BusinessException(DongtanpassErrorCode.QR_INVALID));
		// 서명이 맞는데 패스가 없다. 지금은 패스를 지우지 않으므로 생기지 않아야 한다. 같은 400으로 답한다.
		Pass pass = passes.findById(qr.passId()).orElseThrow(() -> {
			log.warn("연계 학원 QR 확인 실패: reason=pass_missing passId={}", qr.passId());
			return new BusinessException(DongtanpassErrorCode.QR_INVALID);
		});
		requireUsableHere(pass, partnerId);
		// 두 번 통과하지 않게 막는 것은 이 한 곳이다(동시에 와도 하나만 참).
		if (!usedQrTokens.markUsed(qr, now)) {
			log.info("연계 학원 QR 확인 실패: reason=reused passId={}", qr.passId());
			throw new BusinessException(DongtanpassErrorCode.QR_INVALID);
		}
		return view(pass, todayAt(now));
	}

	/**
	 * @throws BusinessException 없는 번호(404), 연계 학원 발급 패스·이 학원에서 이미 할인받은 학생·쓸 수 없는 패스(409)
	 * @throws com.jey.core.shared.RateLimitedException 그 계정의 번호 확인이 한도를 넘었을 때(429)
	 */
	public PartnerVerifiedPassView verifyPassNo(String passNo, AuthenticatedUser user) {
		long partnerId = partnerIdOf(user);
		limiter.acquire(passNoLimit, new Actor.User(user.id()), DongtanpassErrorCode.PASS_NO_VERIFY_LIMITED,
				denial -> DeniedAttempts.record(auditLog,
						() -> AuditEntry.of(DongtanpassAuditAction.PASS_NO_VERIFY_LIMITED), denial));
		Pass pass = passes.findByPassNo(passNo)
			.orElseThrow(() -> new BusinessException(DongtanpassErrorCode.PASS_NOT_FOUND));
		requireUsableHere(pass, partnerId);
		return view(pass, todayAt(clock.instant()));
	}

	/**
	 * 학원이 등록 서류에 적은 학생 이름과 학부모 번호가 패스와 맞는지 따로 답한다(데스크의 본인 확인과 같은 비교, ADR-0033·0035).
	 * 이름은 공백(전각 포함)을 모두 빼고 NFC로 모아 비교하고, 번호는 여럿 중 하나라도 패스의 번호 색인과 같으면 맞다.
	 * 바꾸는 자료가 없다. 최종 판단은 사용 처리가 다시 한다.
	 *
	 * @throws BusinessException 없는 패스(404), 연계 학원 발급 패스·이 학원에서 이미 할인받은 학생·쓸 수 없는 패스(409)
	 * @throws com.jey.core.shared.RateLimitedException 그 계정의 본인 확인이 한도를 넘었을 때(429)
	 */
	public HolderCheckResult holderCheck(long passId, StudentNameKey studentName, List<PhoneNumber> guardianPhones,
			AuthenticatedUser user) {
		long partnerId = partnerIdOf(user);
		// 맞든 틀리든, 패스를 찾기 전에 센다. 없는 패스나 쓸 수 없는 패스로 넣어 보는 것도 한도에 든다.
		limiter.acquire(holderCheckLimit, new Actor.User(user.id()), DongtanpassErrorCode.HOLDER_CHECK_LIMITED,
				denial -> DeniedAttempts.record(auditLog,
						() -> AuditEntry.of(DongtanpassAuditAction.HOLDER_CHECK_LIMITED), denial));
		Pass pass = usablePass(passId, partnerId);
		boolean phoneMatches = guardianPhones.stream()
			.map(phoneProtector::fullIndex)
			.anyMatch(pass.getGuardianPhoneIndex()::equals);
		return new HolderCheckResult(studentName.matches(pass.getStudentName()), phoneMatches);
	}

	/**
	 * 원래 수강료로 받을 금액을 미리 계산한다. 할인율은 그 패스에 남은 발급 때 값이다. 기록을 남기지 않고 세지도 않는다(드러나는 것이 없다).
	 *
	 * @throws BusinessException 없는 패스(404), 연계 학원 발급 패스·이 학원에서 이미 할인받은 학생·쓸 수 없는 패스(409)
	 * @throws BusinessException 원래 수강료가 규칙 밖일 때(400 INVALID_ORIGINAL_FEE)
	 */
	public PartnerDiscount quote(long passId, Money originalFee, AuthenticatedUser user) {
		requireFee(originalFee);
		Pass pass = usablePass(passId, partnerIdOf(user));
		return PartnerDiscount.of(originalFee, pass.getDiscountRate());
	}

	// 수강료 규칙(PartnerDiscount)을 업무 오류로 먼저 본다(발급의 결제 금액과 같은 방식).
	private static void requireFee(Money originalFee) {
		if (originalFee.isZero() || !originalFee.isWholeWon()
				|| originalFee.compareTo(PartnerDiscount.MAX_ORIGINAL_FEE) > 0) {
			throw new BusinessException(DongtanpassErrorCode.INVALID_ORIGINAL_FEE);
		}
	}

	// 확인 뒤의 단계들이 패스를 찾는 길. 확인과 같은 순서로 거부한다(토큰은 없다).
	private Pass usablePass(long passId, long partnerId) {
		Pass pass = passes.findById(passId)
			.orElseThrow(() -> new BusinessException(DongtanpassErrorCode.PASS_NOT_FOUND));
		requireUsableHere(pass, partnerId);
		DeskChecks.requireUsable(pass, todayAt(clock.instant()));
		return pass;
	}

	// 이 패스를 이 학원에서 쓸 수 없는 사정 중, 다른 곳에서는 쓸 수 있는 것들(토큰을 쓰기 전에 본다).
	private void requireUsableHere(Pass pass, long partnerId) {
		if (pass.getDirection() != PassDirection.CAMPUS_ISSUED) {
			throw new BusinessException(DongtanpassErrorCode.PASS_FOR_CAMPUS_USE);
		}
		if (partnerRedemptions.existsByPartnerIdAndStudentId(partnerId, pass.getStudentId())) {
			throw new BusinessException(DongtanpassErrorCode.STUDENT_ALREADY_USED_AT_PARTNER);
		}
	}

	private PartnerVerifiedPassView view(Pass pass, LocalDate today) {
		DeskChecks.requireUsable(pass, today);
		String campusName = catalog.findCampus(pass.getIssuerCampusId())
			.orElseThrow(() -> new IllegalStateException("패스를 발급한 지점이 없다: passId=" + pass.getId()))
			.name();
		return new PartnerVerifiedPassView(pass.getId(), pass.getPassNo(), campusName,
				MaskedName.of(pass.getStudentName()), pass.issuedOn(clock.getZone()), pass.getExpiresOn(),
				pass.getDiscountRate());
	}

	private LocalDate todayAt(Instant now) {
		return LocalDate.ofInstant(now, clock.getZone());
	}

	// 연계 학원 계정에는 학원 식별자가 있다(세션 확인이 그 조합만 통과시킨다). 없으면 설정이 어긋난 것이다.
	static long partnerIdOf(AuthenticatedUser user) {
		if (user.partnerId() == null) {
			throw new IllegalStateException("연계 학원 식별자가 없는 연계 학원 계정이다: accountId=" + user.id());
		}
		return user.partnerId();
	}

}
