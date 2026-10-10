package com.jey.core.config;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
class JpaAuditingConfig {

	// 생성·수정 시각도 애플리케이션 시계에서 얻는다.
	// DB(DATETIME(6))가 마이크로초까지만 저장하므로, 저장한 값과 다시 읽은 값이 같도록 미리 맞춘다.
	@Bean
	DateTimeProvider auditingDateTimeProvider(Clock clock) {
		return () -> Optional.of(clock.instant().truncatedTo(ChronoUnit.MICROS));
	}

}
