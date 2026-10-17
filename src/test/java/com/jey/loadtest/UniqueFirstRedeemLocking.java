package com.jey.loadtest;

import java.util.Optional;

import com.jey.dongtanpass.application.RedeemLocking;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassRepository;
import com.jey.dongtanpass.domain.Redemption;
import com.jey.dongtanpass.domain.RedemptionRepository;

/**
 * 비교 실험의 {@code unique-first} 방식: 잠그지 않고 읽고, 사용 기록을 먼저 넣은 뒤 패스를 고친다(운영 방식이 패스를 먼저 저장하도록
 * 바꾸기 전의 순서). 같은 패스를 동시에 쓰면 사용 기록의 유니크 키가 먼저 막는다. 패스의 버전은 그대로라 링크 새로 만들기와 겹치면
 * 버전 충돌도 난다. 기록 INSERT의 외래 키 확인이 패스 행에 공유 잠금을 걸어, 요청끼리 데드락이 날 수 있다.
 */
public class UniqueFirstRedeemLocking implements RedeemLocking {

	private final PassRepository passes;

	private final RedemptionRepository redemptions;

	public UniqueFirstRedeemLocking(PassRepository passes, RedemptionRepository redemptions) {
		this.passes = passes;
		this.redemptions = redemptions;
	}

	@Override
	public Optional<Pass> load(long passId) {
		return passes.findById(passId);
	}

	@Override
	public Redemption store(Pass pass, Redemption redemption) {
		Redemption saved = redemptions.saveAndFlush(redemption);
		passes.saveAndFlush(pass);
		return saved;
	}

}
