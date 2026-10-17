package com.jey.dongtanpass.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.shared.BusinessException;
import com.jey.dongtanpass.domain.MaskedName;
import com.jey.dongtanpass.domain.Partner;
import com.jey.dongtanpass.domain.PartnerRepository;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 데스크 직원이 학부모의 QR 토큰으로 패스를 확인한다. 사용 처리의 첫 단계다.
 *
 * <p>패스를 받는 지점의 직원과 관리자만 확인한다. 지점을 먼저 보므로 다른 지점 직원의 요청은 토큰을 쓰지 않는다.
 * QR 토큰은 한 번만 통과한다(토큰 ID를 남은 수명만큼 적는다, ADR-0010). 적지 못하면 예외 그대로 통과시키지 않는다.
 * 서명·만료·재사용 실패는 이유를 가리지 않고 400 하나다(이유는 로그에만). 확인에 성공한 토큰은 패스를 쓸 수 없어도 쓴 것이다.
 *
 * <p>트랜잭션을 열지 않는다. 바꾸는 자료가 없고, 토큰 기록은 DB와 무관하다.
 */
@Service
public class DeskPassService {

	private static final Logger log = LoggerFactory.getLogger(DeskPassService.class);

	private final PassRepository passes;

	private final PartnerRepository partners;

	private final PassBenefits passBenefits;

	private final PassQrCodes passQrCodes;

	private final UsedQrTokens usedQrTokens;

	private final Clock clock;

	DeskPassService(PassRepository passes, PartnerRepository partners, PassBenefits passBenefits,
			PassQrCodes passQrCodes, UsedQrTokens usedQrTokens, Clock clock) {
		this.passes = passes;
		this.partners = partners;
		this.passBenefits = passBenefits;
		this.passQrCodes = passQrCodes;
		this.usedQrTokens = usedQrTokens;
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
		if (!usedQrTokens.markUsed(qr, now)) {
			log.info("QR 토큰 확인 실패: reason=reused passId={}", qr.passId());
			throw new BusinessException(DongtanpassErrorCode.QR_INVALID);
		}
		// 서명이 맞는데 패스가 없다. 지금은 패스를 지우지 않으므로 생기지 않아야 한다. 데스크에는 같은 400으로 답한다.
		Pass pass = passes.findById(qr.passId()).orElseThrow(() -> {
			log.warn("QR 토큰 확인 실패: reason=pass_missing passId={}", qr.passId());
			return new BusinessException(DongtanpassErrorCode.QR_INVALID);
		});
		return usableView(pass, benefit, LocalDate.ofInstant(now, clock.getZone()));
	}

	private PassBenefit requireProvidingCampus(AuthenticatedUser user) {
		PassBenefit benefit = passBenefits.current();
		if (!user.campusScope().includes(benefit.campusId())) {
			throw new BusinessException(DongtanpassErrorCode.CAMPUS_NOT_PROVIDING);
		}
		return benefit;
	}

	private VerifiedPassView usableView(Pass pass, PassBenefit benefit, LocalDate today) {
		DongtanpassErrorCode unusable = switch (pass.stateOn(today)) {
			case ISSUED -> null;
			case EXPIRED -> DongtanpassErrorCode.PASS_EXPIRED;
			case CANCELED -> DongtanpassErrorCode.PASS_CANCELED;
			case REDEEMED -> DongtanpassErrorCode.PASS_ALREADY_REDEEMED;
		};
		if (unusable != null) {
			throw new BusinessException(unusable);
		}
		Partner partner = partners.findById(pass.getPartnerId())
			.orElseThrow(() -> new IllegalStateException("패스의 연계 학원이 없다: passId=" + pass.getId()));
		return new VerifiedPassView(pass.getId(), pass.getPassNo(), partner.getName(),
				MaskedName.of(pass.getStudentName()), pass.issuedOn(clock.getZone()), pass.getExpiresOn(),
				benefit.discountRate());
	}

}
