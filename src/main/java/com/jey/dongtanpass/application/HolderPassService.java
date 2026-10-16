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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 학부모가 링크 토큰으로 패스를 연다. 학부모는 로그인하지 않는다. 링크 토큰이 열쇠다.
 *
 * <p>없는 토큰, 모양이 틀린 토큰, 링크를 새로 만들어 막힌 토큰은 모두 같은 404다(어느 쪽인지 드러내지 않는다).
 * 학생 이름은 가려서 준다. 학원을 꺼도 발급된 패스는 열린다(ADR-0034).
 */
@Service
@Transactional(readOnly = true)
public class HolderPassService {

	private final PassRepository passes;

	private final PartnerRepository partners;

	private final PassBenefits passBenefits;

	private final Clock clock;

	HolderPassService(PassRepository passes, PartnerRepository partners, PassBenefits passBenefits, Clock clock) {
		this.passes = passes;
		this.partners = partners;
		this.passBenefits = passBenefits;
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

	// 모양을 먼저 본다. 해시는 ASCII가 아닌 글자를 ?로 바꾸므로 그 전에 걸러야 한다.
	private Pass passOf(String rawToken) {
		if (!LinkToken.isWellFormed(rawToken)) {
			throw new BusinessException(DongtanpassErrorCode.PASS_NOT_FOUND);
		}
		return passes.findByLinkTokenHash(LinkToken.hashOf(rawToken))
			.orElseThrow(() -> new BusinessException(DongtanpassErrorCode.PASS_NOT_FOUND));
	}

}
