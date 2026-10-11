package com.jey.core.shared;

import java.time.Duration;
import java.util.Objects;

import org.springframework.http.HttpStatus;

/**
 * 횟수 한도를 넘어 막힌 요청. 응답 변환기가 남은 시간을 {@code Retry-After} 헤더(초)로 싣는다.
 */
public class RateLimitedException extends BusinessException {

	private final Duration retryAfter;

	/**
	 * @param errorCode 상태가 429인 에러 코드
	 * @param retryAfter 다시 할 수 있을 때까지 남은 시간. 0보다 길어야 한다
	 * @throws IllegalArgumentException 에러 코드의 상태가 429가 아니거나 남은 시간이 0 이하일 때
	 */
	public RateLimitedException(ErrorCode errorCode, Duration retryAfter) {
		super(errorCode);
		Objects.requireNonNull(retryAfter, "retryAfter");
		// 다른 상태의 응답에 Retry-After만 붙어 나가는 것을 막는다.
		if (errorCode.status() != HttpStatus.TOO_MANY_REQUESTS) {
			throw new IllegalArgumentException("횟수 한도에 쓰는 에러 코드의 상태는 429여야 한다: " + errorCode.code());
		}
		if (retryAfter.isZero() || retryAfter.isNegative()) {
			throw new IllegalArgumentException("남은 시간은 0보다 길어야 한다: " + retryAfter);
		}
		this.retryAfter = retryAfter;
	}

	public Duration getRetryAfter() {
		return retryAfter;
	}

	/** 헤더에 싣는 값. 1초 미만도 1초로 올린다(0초라고 알리면 바로 다시 보내 또 막힌다). */
	public long retryAfterSeconds() {
		return Math.max(1, (retryAfter.toMillis() + 999) / 1000);
	}

}
