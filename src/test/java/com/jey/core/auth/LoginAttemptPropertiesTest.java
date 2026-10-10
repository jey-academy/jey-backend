package com.jey.core.auth;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class LoginAttemptPropertiesTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

	@Test
	void 설정이_없으면_기본값을_쓴다() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			LoginAttemptProperties properties = context.getBean(LoginAttemptProperties.class);
			assertThat(properties.window()).isEqualTo(Duration.ofMinutes(15));
			assertThat(properties.maxFailuresPerLoginIdAndIp()).isEqualTo(5);
			assertThat(properties.maxFailuresPerIp()).isEqualTo(30);
		});
	}

	@Test
	void 설정_키_이름대로_값을_읽는다() {
		runner.withPropertyValues(
				"jey.auth.login-attempt-limit.window=10m",
				"jey.auth.login-attempt-limit.max-failures-per-login-id-and-ip=3",
				"jey.auth.login-attempt-limit.max-failures-per-ip=20")
				.run(context -> {
					LoginAttemptProperties properties = context.getBean(LoginAttemptProperties.class);
					assertThat(properties.window()).isEqualTo(Duration.ofMinutes(10));
					assertThat(properties.maxFailuresPerLoginIdAndIp()).isEqualTo(3);
					assertThat(properties.maxFailuresPerIp()).isEqualTo(20);
				});
	}

	// 단위를 빼먹은 "15"는 15밀리초로 읽힌다. 그런 값으로 뜨면 제한이 사실상 꺼진다.
	@Test
	void 기간에_단위를_빼먹으면_뜨지_않는다() {
		runner.withPropertyValues("jey.auth.login-attempt-limit.window=15")
				.run(context -> assertThat(context).hasFailed().getFailure().rootCause()
						.hasMessageContaining("login-attempt-limit.window"));
	}

	@Test
	void 한도는_1_이상이어야_한다() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new LoginAttemptProperties(Duration.ofMinutes(15), 0, 30))
				.withMessageContaining("max-failures-per-login-id-and-ip");
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new LoginAttemptProperties(Duration.ofMinutes(15), 5, 0))
				.withMessageContaining("max-failures-per-ip");
	}

	// 한 계정의 한도가 IP 전체의 한도보다 크면 IP 한도가 먼저 걸려 계정 한도가 의미가 없다. 둘을 바꿔 적은 실수일 가능성이 크다.
	@Test
	void IP_한도는_아이디별_한도보다_작을_수_없다() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new LoginAttemptProperties(Duration.ofMinutes(15), 10, 5))
				.withMessageContaining("max-failures-per-ip");
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(LoginAttemptProperties.class)
	static class Config {
	}

}
