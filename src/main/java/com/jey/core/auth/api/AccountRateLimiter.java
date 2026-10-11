package com.jey.core.auth.api;

import java.util.Objects;
import java.util.function.Consumer;

import com.jey.core.shared.Actor;
import com.jey.core.shared.ErrorCode;
import com.jey.core.shared.RateLimitedException;

/**
 * 계정마다 횟수를 세서 한도를 넘는 요청을 막는다.
 *
 * <p>일을 하기 <b>전에</b> {@link #acquire}로 센다. "한 뒤에 센다"로 만들면 동시에 보낸 요청이 모두 확인을 통과한다.
 * 실패만 세고 싶으면 먼저 세고, 실패가 아니었을 때 받아 둔 {@link Permit}으로 {@link #refund}한다.
 *
 * <p>횟수를 세지 못하면(저장소에 닿지 못함) 예외를 던진다. 세지 못한 채로 통과시키지 않는다.
 */
public interface AccountRateLimiter {

	/**
	 * 한 번 센다. 이미 한도에 닿아 있으면 세지 않고 {@link RateLimitedException}을 던진다.
	 *
	 * <p>막힌 사실을 남기는 일은 {@code onFirstBlock}으로 받는다. 한 구간에서 처음 막힌 요청에서만 부른다
	 * (막힌 계정이 계속 보내도 한 번이고, 동시에 막힌 요청 중에서도 하나만이다).
	 * 그 일이 실패하면 그 실패를 던지고, 다음에 막힌 요청에서 다시 부른다. 부르다가 서버가 죽은 경우에도 얼마 뒤 다시 부른다.
	 *
	 * @param limitedCode 막혔을 때 던질 예외의 에러 코드. 상태가 429여야 한다
	 * @param onFirstBlock 이 구간에서 처음 막혔을 때 할 일(감사 로그에 남기기 등). 던질 예외를 받는다
	 * @return 센 한 번. 되돌릴 때 쓴다
	 * @throws RateLimitedException 한도에 닿아 막혔을 때
	 * @throws IllegalArgumentException 에러 코드의 상태가 429가 아닐 때. 세지 않는다
	 */
	Permit acquire(RateLimit limit, Actor.User account, ErrorCode limitedCode,
			Consumer<RateLimitedException> onFirstBlock);

	/**
	 * 센 한 번을 되돌린다. 그 한 번을 센 구간이 이미 끝났으면 아무 일도 하지 않는다.
	 * 구간이 바뀐 뒤에 되돌리면 새 구간에서 센 것이 지워지기 때문이다.
	 */
	void refund(Permit permit);

	/**
	 * {@link #acquire}가 센 한 번.
	 *
	 * @param window 그 한 번을 센 구간의 표식
	 */
	record Permit(RateLimit limit, Actor.User account, String window) {

		public Permit {
			Objects.requireNonNull(limit, "limit");
			Objects.requireNonNull(account, "account");
			Objects.requireNonNull(window, "window");
		}

	}

}
