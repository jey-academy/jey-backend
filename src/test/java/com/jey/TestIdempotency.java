package com.jey;

import java.util.UUID;

import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 테스트 요청에 {@code Idempotency-Key}를 싣는다. 키가 필요한 POST는 키 없이 보내면 400이다.
 */
public final class TestIdempotency {

	public static final String HEADER_NAME = "Idempotency-Key";

	private TestIdempotency() {
	}

	/** 요청마다 새 키를 싣는다. 같은 요청을 되풀이하는 테스트에서도 매번 따로 실행된다. */
	public static RequestPostProcessor idempotencyKey() {
		return idempotencyKey(UUID.randomUUID().toString());
	}

	/** 정한 키를 싣는다. 같은 키로 다시 보내는 경우를 볼 때 쓴다. */
	public static RequestPostProcessor idempotencyKey(String key) {
		return request -> {
			request.addHeader(HEADER_NAME, key);
			return request;
		};
	}

}
