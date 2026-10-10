package com.jey.core.config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JpaAuditingConfigTest {

	// DB(DATETIME(6))는 마이크로초까지만 저장한다. 미리 잘라 두지 않으면 저장한 값과 다시 읽은 값이 달라진다.
	@Test
	void 감사_시각은_주입된_시계에서_마이크로초까지_잘라_얻는다() {
		Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00.123456789Z"), ZoneId.of("Asia/Seoul"));

		var provider = new JpaAuditingConfig().auditingDateTimeProvider(clock);

		assertThat(provider.getNow()).contains(Instant.parse("2026-01-01T00:00:00.123456Z"));
	}

}
