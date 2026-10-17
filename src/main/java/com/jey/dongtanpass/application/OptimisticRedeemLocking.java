package com.jey.dongtanpass.application;

import java.util.Optional;

import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassRepository;
import com.jey.dongtanpass.domain.Redemption;
import com.jey.dongtanpass.domain.RedemptionRepository;
import org.springframework.stereotype.Component;

/**
 * 운영 방식: 패스를 잠그지 않고 읽고(낙관적 락, 패스의 버전), 저장할 때 패스를 먼저 고친 뒤 사용 기록을 넣는다.
 *
 * <p>순서가 중요하다. 사용 기록을 먼저 넣으면 외래 키 확인이 패스 행에 공유 잠금을 걸고, 같은 패스를 동시에 쓰는 두 요청이
 * 서로의 공유 잠금을 기다리는 데드락이 난다(Hibernate는 따로 flush하지 않으면 INSERT를 UPDATE보다 먼저 보낸다).
 * 패스 UPDATE를 먼저 보내면 먼저 온 요청이 행을 배타 잠금하고, 진 요청은 기다렸다가 버전 충돌로 끝난다.
 * 같은 학생을 먼저 쓴 요청이 있으면 기록을 넣을 때 유니크 키에 걸린다(커밋 때까지 미루지 않는다).
 */
@Component
class OptimisticRedeemLocking implements RedeemLocking {

	private final PassRepository passes;

	private final RedemptionRepository redemptions;

	OptimisticRedeemLocking(PassRepository passes, RedemptionRepository redemptions) {
		this.passes = passes;
		this.redemptions = redemptions;
	}

	@Override
	public Optional<Pass> load(long passId) {
		return passes.findById(passId);
	}

	@Override
	public Redemption store(Pass pass, Redemption redemption) {
		passes.saveAndFlush(pass);
		return redemptions.saveAndFlush(redemption);
	}

}
