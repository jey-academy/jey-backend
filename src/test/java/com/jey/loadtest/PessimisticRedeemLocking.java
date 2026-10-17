package com.jey.loadtest;

import java.util.Optional;

import com.jey.dongtanpass.application.RedeemLocking;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassRepository;
import com.jey.dongtanpass.domain.Redemption;
import com.jey.dongtanpass.domain.RedemptionRepository;

/**
 * 비교 실험의 {@code pessimistic} 방식: 패스를 {@code SELECT … FOR UPDATE}로 읽어 같은 패스의 요청을 줄 세운다. 저장 순서는 운영 방식과 같다(패스 먼저).
 * 뒤에 온 요청은 앞 요청이 커밋할 때까지 DB 연결을 쥔 채 기다렸다가, 바뀐 상태(사용됨)를 읽고 409로 끝난다.
 */
public class PessimisticRedeemLocking implements RedeemLocking {

	private final PassRepository passes;

	private final RedemptionRepository redemptions;

	public PessimisticRedeemLocking(PassRepository passes, RedemptionRepository redemptions) {
		this.passes = passes;
		this.redemptions = redemptions;
	}

	@Override
	public Optional<Pass> load(long passId) {
		return passes.findByIdForUpdate(passId);
	}

	@Override
	public Redemption store(Pass pass, Redemption redemption) {
		passes.saveAndFlush(pass);
		return redemptions.saveAndFlush(redemption);
	}

}
