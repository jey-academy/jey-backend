package com.jey.core.auth.api;

import java.time.Duration;
import java.util.Optional;

/**
 * 계정마다 횟수를 세서 한도를 넘는 요청을 막는다.
 *
 * <p>일을 하기 <b>전에</b> {@link #tryAcquire}로 센다. "한 뒤에 센다"로 만들면 동시에 보낸 요청이 모두 확인을 통과한다.
 * 실패만 세고 싶으면 먼저 세고, 실패가 아니었을 때 {@link #refund}로 되돌린다.
 *
 * <p>횟수를 세지 못하면(저장소에 닿지 못함) 예외를 던진다. 세지 못한 채로 통과시키지 않는다.
 */
public interface AccountRateLimiter {

	/**
	 * 한 번 센다. 이미 한도에 닿아 있으면 세지 않고 막는다.
	 *
	 * @return 막혔으면 남은 시간. 통과했으면 빈 값
	 */
	Optional<Blocked> tryAcquire(RateLimit limit, long accountId);

	/**
	 * 방금 센 한 번을 되돌린다. 센 것이 없으면 아무 일도 하지 않는다.
	 */
	void refund(RateLimit limit, long accountId);

	/**
	 * "이 구간에서 막힌 것을 알렸다"는 표시를 지운다. 다음에 막히는 요청이 다시 {@link Blocked#firstInWindow()}를 받는다.
	 * 막힌 사실을 기록하려다 실패했을 때 부른다. 부르지 않으면 그 구간의 기록이 영영 빠진다.
	 */
	void forgetBlockNotice(RateLimit limit, long accountId);

	/**
	 * @param retryAfter 다시 할 수 있을 때까지 남은 시간
	 * @param firstInWindow 이 구간에서 처음 막힌 것인지. 막힌 사실을 구간마다 한 번만 기록하는 데 쓴다.
	 * 동시에 막힌 요청 중에서도 하나만 참이다
	 */
	record Blocked(Duration retryAfter, boolean firstInWindow) {
	}

}
