package com.jey.core.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class TimeConfig {

	// 현재 시각은 항상 이 시계에서 얻는다(LocalDate.now(clock), clock.instant()).
	// 서버의 기본 시간대와 무관하게 한국 날짜가 나오고, 테스트에서는 고정된 시계로 바꿔 끼울 수 있다.
	@Bean
	Clock clock() {
		return Clock.system(ZoneId.of("Asia/Seoul"));
	}

}
