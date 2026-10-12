package com.jey.core.web;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

// 설정 키 이름과 기본값, 잘못된 값으로는 서버가 뜨지 않는다는 것을 Spring Boot가 값을 읽는 과정까지 거쳐서 본다.
// 키 이름이 틀리면 적어 넣은 값이 조용히 무시되고 기본값으로 뜬다. 스프링 컨텍스트만 띄우므로 Docker가 필요 없다.
class IdempotencyPropertiesBindingTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

	@Test
	void 설정이_없으면_기본값으로_뜬다() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			IdempotencyProperties properties = context.getBean(IdempotencyProperties.class);
			assertThat(properties.retention()).isEqualTo(Duration.ofHours(24));
			assertThat(properties.processingLease()).isEqualTo(Duration.ofMinutes(2));
		});
	}

	@Test
	void 설정_키_이름대로_값을_읽는다() {
		runner.withPropertyValues("jey.web.idempotency.retention=6h", "jey.web.idempotency.processing-lease=45s")
				.run(context -> {
					assertThat(context).hasNotFailed();
					IdempotencyProperties properties = context.getBean(IdempotencyProperties.class);
					assertThat(properties.retention()).isEqualTo(Duration.ofHours(6));
					assertThat(properties.processingLease()).isEqualTo(Duration.ofSeconds(45));
				});
	}

	// 단위를 빼먹은 "24"는 24밀리초로 읽힌다. 그대로 뜨면 다시 보낸 요청이 그대로 다시 실행된다.
	@Test
	void 단위를_빼먹은_보관_기간으로는_뜨지_않는다() {
		runner.withPropertyValues("jey.web.idempotency.retention=24").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("retention");
		});
	}

	// "2m"을 "2h"로 잘못 적은 경우다. 서버가 죽은 요청의 키가 두 시간 동안 "처리 중"으로 거부된다.
	@Test
	void 임대가_너무_길면_뜨지_않는다() {
		runner.withPropertyValues("jey.web.idempotency.processing-lease=2h").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("processing-lease");
		});
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(IdempotencyProperties.class)
	static class Config {

	}

}
