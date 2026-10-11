package com.jey.core.web;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 멱등 키 설정({@code jey.web.idempotency.*}, ADR-0027). 값이 잘못되면 서버가 뜨지 않는다.
 *
 * @param retention 끝난 요청의 응답을 보관하는 기간. 이 동안 같은 키의 요청은 다시 실행되지 않고 보관한 응답을 받는다
 * @param processingLease 처리 중이라고 표시해 두는 기간. 처리하던 서버가 죽으면 이 시간 뒤에 같은 키로 다시 실행할 수 있다.
 * 요청 하나가 이보다 오래 걸리면 같은 키의 요청이 겹쳐 실행될 수 있다
 */
@ConfigurationProperties("jey.web.idempotency")
record IdempotencyProperties(@DefaultValue("24h") Duration retention, @DefaultValue("2m") Duration processingLease) {

	private static final String PREFIX = "jey.web.idempotency.";

	// 단위를 빼먹은 "24"는 24밀리초로 읽힌다. 그런 값으로 뜨면 다시 보낸 요청이 그대로 다시 실행된다.
	private static final Duration MIN_RETENTION = Duration.ofMinutes(1);

	private static final Duration MIN_PROCESSING_LEASE = Duration.ofSeconds(10);

	IdempotencyProperties {
		if (retention == null || retention.compareTo(MIN_RETENTION) < 0) {
			throw new IllegalArgumentException(PREFIX + "retention은 1분 이상이어야 한다(단위를 붙였는지 확인): " + retention);
		}
		if (processingLease == null || processingLease.compareTo(MIN_PROCESSING_LEASE) < 0) {
			throw new IllegalArgumentException(
					PREFIX + "processing-lease는 10초 이상이어야 한다(단위를 붙였는지 확인): " + processingLease);
		}
		if (processingLease.compareTo(retention) >= 0) {
			throw new IllegalArgumentException(PREFIX + "processing-lease(" + processingLease + ")는 retention("
					+ retention + ")보다 짧아야 한다");
		}
	}

}
