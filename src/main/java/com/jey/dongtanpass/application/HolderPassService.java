package com.jey.dongtanpass.application;

import java.time.Clock;
import java.time.LocalDate;

import com.jey.core.shared.BusinessException;
import com.jey.dongtanpass.domain.LinkToken;
import com.jey.dongtanpass.domain.MaskedName;
import com.jey.dongtanpass.domain.Partner;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PartnerRepository;
import com.jey.dongtanpass.domain.PassRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 학부모가 링크 토큰으로 패스를 열고, 데스크에 보여 줄 서명 QR 토큰을 받는다. 학부모는 로그인하지 않는다. 링크 토큰이 열쇠다.
 *
 * <p>없는 토큰, 모양이 틀린 토큰, 링크를 새로 만들어 막힌 토큰은 모두 같은 404다(어느 쪽인지 드러내지 않는다).
 * 학생 이름은 가려서 준다. 학원을 꺼도 발급된 패스는 열리고 QR도 받는다(ADR-0034). QR은 쓸 수 있는 패스에만 준다(ADR-0010).
 */
@Service
@Transactional(readOnly = true)
public class HolderPassService {

	private static final Logger log = LoggerFactory.getLogger(HolderPassService.class);

	private final PassRepository passes;

	private final PartnerRepository partners;

	private final PassBenefits passBenefits;

	private final PassQrCodes passQrCodes;

	private final Clock clock;

	HolderPassService(PassRepository passes, PartnerRepository partners, PassBenefits passBenefits,
			PassQrCodes passQrCodes, Clock clock) {
		this.passes = passes;
		this.partners = partners;
		this.passBenefits = passBenefits;
		this.passQrCodes = passQrCodes;
		this.clock = clock;
	}

	/**
	 * @throws BusinessException 그 토큰의 패스가 없을 때(404)
	 */
	public HolderPassView view(String rawToken) {
		Pass pass = passOf(rawToken);
		Partner partner = partners.findById(pass.getPartnerId())
			.orElseThrow(() -> new IllegalStateException("패스의 연계 학원이 없다: passId=" + pass.getId()));
		PassBenefit benefit = passBenefits.current();
		return new HolderPassView(pass.getPassNo(), partner.getName(), MaskedName.of(pass.getStudentName()),
				pass.getExpiresOn(), pass.stateOn(LocalDate.now(clock)), benefit.discountRate(), benefit.campusName(),
				ConsentNotice.CURRENT.version(), ConsentNotice.CURRENT.text());
	}

	/**
	 * 데스크에 보여 줄 QR 토큰을 준다. 부를 때마다 새 토큰이다(수명 {@link PassQrCodes#TTL}).
	 *
	 * @throws BusinessException 그 토큰의 패스가 없을 때(404), 기간이 지났거나 취소됐거나 사용된 패스일 때(409)
	 */
	public QrCode qr(String rawToken) {
		Pass pass = passOf(rawToken);
		DongtanpassErrorCode unusable = switch (pass.stateOn(LocalDate.now(clock))) {
			case ISSUED -> null;
			case EXPIRED -> DongtanpassErrorCode.PASS_EXPIRED;
			case CANCELED -> DongtanpassErrorCode.PASS_CANCELED;
			case REDEEMED -> DongtanpassErrorCode.PASS_ALREADY_REDEEMED;
		};
		if (unusable != null) {
			throw new BusinessException(unusable);
		}
		return passQrCodes.issue(pass.getId(), clock.instant());
	}

	// 모양을 먼저 본다. 해시는 ASCII가 아닌 글자를 ?로 바꾸므로 그 전에 걸러야 한다.
	// 응답은 어느 경우든 같은 404다. 운영에서 "링크가 안 열린다"는 문의를 가리려고 이유만 로그에 남긴다(토큰과 해시는 남기지 않는다).
	// 막힌 옛 링크(다시 받았거나 연계 학원이 새로 만든 것)는 not_found다. 해시만 저장하므로 없는 링크와 구별되지 않는다.
	private Pass passOf(String rawToken) {
		if (!LinkToken.isWellFormed(rawToken)) {
			log.info("학부모 링크로 패스를 찾지 못했다: reason=malformed");
			throw new BusinessException(DongtanpassErrorCode.PASS_NOT_FOUND);
		}
		return passes.findByLinkTokenHash(LinkToken.hashOf(rawToken)).orElseThrow(() -> {
			log.info("학부모 링크로 패스를 찾지 못했다: reason=not_found");
			return new BusinessException(DongtanpassErrorCode.PASS_NOT_FOUND);
		});
	}

}
