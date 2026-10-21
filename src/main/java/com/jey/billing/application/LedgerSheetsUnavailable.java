package com.jey.billing.application;

import java.time.Duration;

/** 구글이 응답하지 않았다(시간 초과, 연결 끊김) 또는 408·5xx·429로 답했다. 간격을 두고 다시 시도한다. */
public class LedgerSheetsUnavailable extends RuntimeException {

	private final Duration retryAfter;

	public LedgerSheetsUnavailable(String message, Duration retryAfter, Throwable cause) {
		super(message, cause);
		this.retryAfter = retryAfter;
	}

	/** 구글이 알려 준 기다릴 시간(429의 {@code Retry-After}). 없으면 {@code null}. */
	public Duration retryAfter() {
		return retryAfter;
	}

}
