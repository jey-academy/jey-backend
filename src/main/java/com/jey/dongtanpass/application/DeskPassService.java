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
import com.jey.core.shared.Actor;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.PhoneNumber;
import com.jey.core.shared.PhoneProtector;
import com.jey.dongtanpass.domain.MaskedName;
import com.jey.dongtanpass.domain.Partner;
import com.jey.dongtanpass.domain.PartnerRepository;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassRepository;
import com.jey.dongtanpass.domain.StudentNameKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * 데스크 직원이 학부모의 QR 토큰이나 패스 번호로 패스를 확인하고, 학생을 등록하기 전에 이름과 학부모 번호를 패스와 미리 맞춰 본다.
 * 사용 처리의 앞 단계들이다(ADR-0033).
 *
 * <p>패스를 받는 지점의 직원과 관리자만 확인한다. 지점을 먼저 보므로 다른 지점 직원의 요청은 토큰을 쓰지 않는다.
 * QR 토큰은 한 번만 통과한다(토큰 ID를 남은 수명만큼 적는다, ADR-0010). 적지 못하면 예외 그대로 통과시키지 않는다.
 * 패스와 연계 학원을 먼저 읽고 토큰을 적는다. 읽다 실패하면(DB 장애, 패스 없음) 토큰을 쓰지 않아 같은 QR로 다시 할 수 있다.
 * JEY 발급 패스도 토큰을 적기 전에 409({@code PASS_FOR_PARTNER_USE})로 거부한다(그 QR은 연계 학원에서 그대로 쓴다, ADR-0040).
 * 서명·만료·재사용 실패는 이유를 가리지 않고 400 하나다(이유는 로그에만). 확인에 성공한 토큰은 패스를 쓸 수 없어도 쓴 것이다.
 *
 * <p>패스 번호는 짧아 넣어 보며 찾을 수 있으므로 계정마다 횟수를 센다(맞든 틀리든, 찾기 전에). 미리 확인도 번호를 넣어 보며
 * 패스 주인을 알아낼 수 있으므로 따로 센다. 처음 막힌 것은 감사 로그에 남긴다.
 *
 * <p>트랜잭션을 열지 않는다. 바꾸는 자료가 없고, 토큰 기록은 DB와 무관하고, 한도에 막힌 기록은 트랜잭션 밖에서 남긴다.
 */
@Service
@EnableConfigurationProperties({ PassNoVerifyProperties.class, HolderCheckProperties.class })
public class DeskPassService {

	private static final Logger log = LoggerFactory.getLogger(DeskPassService.class);

	private final PassRepository passes;

	private final PartnerRepository partners;

	private final PassBenefits passBenefits;

	private final PassQrCodes passQrCodes;

	private final UsedQrTokens usedQrTokens;

	private final AccountRateLimiter limiter;

	private final RateLimit passNoLimit;

	private final RateLimit holderCheckLimit;

	private final AuditLog auditLog;

	private final PhoneProtector phoneProtector;

	private final Clock clock;

	DeskPassService(PassRepository passes, PartnerRepository partners, PassBenefits passBenefits,
			PassQrCodes passQrCodes, UsedQrTokens usedQrTokens, AccountRateLimiter limiter,
			PassNoVerifyProperties passNoVerify, HolderCheckProperties holderCheck, AuditLog auditLog,
			PhoneProtector phoneProtector, Clock clock) {
		this.passes = passes;
		this.partners = partners;
		this.passBenefits = passBenefits;
		this.passQrCodes = passQrCodes;
		this.usedQrTokens = usedQrTokens;
		this.limiter = limiter;
		this.passNoLimit = passNoVerify.limit();
		this.holderCheckLimit = holderCheck.limit();
		this.auditLog = auditLog;
		this.phoneProtector = phoneProtector;
		this.clock = clock;
	}

	/**
	 * @throws BusinessException 패스를 받지 않는 지점의 직원(403), 무효이거나 이미 쓴 토큰(400),
	 * 기간이 지났거나 취소됐거나 사용된 패스(409)
	 */
	public VerifiedPassView verifyQr(String qrToken, AuthenticatedUser user) {
		PassBenefit benefit = requireProvidingCampus(user);
		Instant now = clock.instant();
		VerifiedQr qr = passQrCodes.verify(qrToken, now)
			.orElseThrow(() -> new BusinessException(DongtanpassErrorCode.QR_INVALID));
		// 서명이 맞는데 패스가 없다. 지금은 패스를 지우지 않으므로 생기지 않아야 한다. 데스크에는 같은 400으로 답한다.
		Pass pass = passes.findById(qr.passId()).orElseThrow(() -> {
			log.warn("QR 토큰 확인 실패: reason=pass_missing passId={}", qr.passId());
			return new BusinessException(DongtanpassErrorCode.QR_INVALID);
		});
		DeskChecks.requirePartnerIssued(pass);
		Partner partner = partnerOf(pass);
		// 두 번 통과하지 않게 막는 것은 이 한 곳이다(동시에 와도 하나만 참). 쓸 수 없는 패스여도 여기까지 왔으면 쓴 것이다.
		if (!usedQrTokens.markUsed(qr, now)) {
			log.info("QR 토큰 확인 실패: reason=reused passId={}", qr.passId());
			throw new BusinessException(DongtanpassErrorCode.QR_INVALID);
		}
		return usableView(pass, partner, benefit, todayAt(now));
	}

	/**
	 * @throws BusinessException 패스를 받지 않는 지점의 직원(403), 없는 번호(404), 기간이 지났거나 취소됐거나 사용된 패스(409)
	 * @throws com.jey.core.shared.RateLimitedException 그 계정의 번호 확인이 한도를 넘었을 때(429)
	 */
	public VerifiedPassView verifyPassNo(String passNo, AuthenticatedUser user) {
		PassBenefit benefit = requireProvidingCampus(user);
		limiter.acquire(passNoLimit, new Actor.User(user.id()), DongtanpassErrorCode.PASS_NO_VERIFY_LIMITED,
				denial -> DeniedAttempts.record(auditLog,
						() -> AuditEntry.of(DongtanpassAuditAction.PASS_NO_VERIFY_LIMITED), denial));
		Pass pass = passes.findByPassNo(passNo)
			.orElseThrow(() -> new BusinessException(DongtanpassErrorCode.PASS_NOT_FOUND));
		DeskChecks.requirePartnerIssued(pass);
		return usableView(pass, partnerOf(pass), benefit, todayAt(clock.instant()));
	}

	/**
	 * 등록 폼에 적은 학생 이름과 학부모 번호가 패스와 맞는지 따로 답한다(ADR-0033). 이름은 공백(전각 포함)을 모두 빼고 NFC로 모아 비교하고,
	 * 번호는 여럿 중 하나라도 패스를 받을 때 적은 번호와 같으면 맞다(1개 이상 넘긴다. 빈 목록이면 늘 맞지 않음). 바꾸는 자료가 없고 결과는 감사 로그에 남기지 않는다.
	 *
	 * @throws BusinessException 패스를 받지 않는 지점의 직원(403), 없는 패스(404), 기간이 지났거나 취소됐거나 사용된 패스(409)
	 * @throws com.jey.core.shared.RateLimitedException 그 계정의 미리 확인이 한도를 넘었을 때(429)
	 */
	public HolderCheckResult holderCheck(long passId, StudentNameKey studentName, List<PhoneNumber> guardianPhones,
			AuthenticatedUser user) {
		requireProvidingCampus(user);
		// 맞든 틀리든, 패스를 찾기 전에 센다. 없는 패스나 쓸 수 없는 패스로 넣어 보는 것도 한도에 든다.
		limiter.acquire(holderCheckLimit, new Actor.User(user.id()), DongtanpassErrorCode.HOLDER_CHECK_LIMITED,
				denial -> DeniedAttempts.record(auditLog,
						() -> AuditEntry.of(DongtanpassAuditAction.HOLDER_CHECK_LIMITED), denial));
		Pass pass = passes.findById(passId)
			.orElseThrow(() -> new BusinessException(DongtanpassErrorCode.PASS_NOT_FOUND));
		DeskChecks.requirePartnerIssued(pass);
		DeskChecks.requireUsable(pass, todayAt(clock.instant()));
		boolean phoneMatches = guardianPhones.stream()
			.map(phoneProtector::fullIndex)
			.anyMatch(pass.getGuardianPhoneIndex()::equals);
		return new HolderCheckResult(studentName.matches(pass.getStudentName()), phoneMatches);
	}

	private PassBenefit requireProvidingCampus(AuthenticatedUser user) {
		PassBenefit benefit = passBenefits.current();
		DeskChecks.requireProvidingCampus(benefit, user);
		return benefit;
	}

	// 오늘은 그 요청이 읽은 시각 하나에서 구한다(QR은 서명 확인·토큰 기록과 같은 시각).
	private LocalDate todayAt(Instant now) {
		return LocalDate.ofInstant(now, clock.getZone());
	}

	private Partner partnerOf(Pass pass) {
		return partners.findById(pass.getPartnerId())
			.orElseThrow(() -> new IllegalStateException("패스의 연계 학원이 없다: passId=" + pass.getId()));
	}

	private VerifiedPassView usableView(Pass pass, Partner partner, PassBenefit benefit, LocalDate today) {
		DeskChecks.requireUsable(pass, today);
		return new VerifiedPassView(pass.getId(), pass.getPassNo(), partner.getName(),
				MaskedName.of(pass.getStudentName()), pass.issuedOn(clock.getZone()), pass.getExpiresOn(),
				benefit.discountRate());
	}

}
