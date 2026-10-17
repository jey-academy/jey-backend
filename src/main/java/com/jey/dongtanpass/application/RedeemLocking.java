package com.jey.dongtanpass.application;

import java.util.Optional;
import java.util.function.Supplier;

import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.Redemption;

/**
 * 사용 처리에서 같은 패스를 동시에 쓰는 요청을 어떻게 가르는가: 패스를 읽는 법, 저장하는 순서, 트랜잭션 바깥의 잠금.
 * 운영 구현은 {@link OptimisticRedeemLocking} 하나다. 다른 방식은 비교 실험(부하 시험)에서만 바꿔 끼운다.
 *
 * <p>어느 방식이든 사용 기록의 두 유니크 키(패스 하나, 학생 한 명)가 마지막에 막는다. 확인 순서와 예외 변환
 * (유니크 위반 → 409, 버전 충돌 → 다시 읽은 상태로 답함)은 {@link PassRedeemService}가 맡는다.
 */
public interface RedeemLocking {

	/**
	 * 사용 처리의 트랜잭션 전체를 감싼다. 트랜잭션 밖에서 잡아야 하는 잠금(분산 락 등)을 여기서 잡고 푼다.
	 * 기본은 그대로 실행한다.
	 */
	default <T> T guard(long passId, Supplier<T> work) {
		return work.get();
	}

	/** 트랜잭션 안에서 사용 처리할 패스를 읽는다. 없으면 비어 있다. */
	Optional<Pass> load(long passId);

	/**
	 * 사용됨으로 바꾼 패스와 그 사용 기록을 저장한다(곧바로 DB에 보낸다). 저장 순서가 잠금 순서를 정한다.
	 *
	 * @return 저장한 사용 기록
	 */
	Redemption store(Pass pass, Redemption redemption);

}
