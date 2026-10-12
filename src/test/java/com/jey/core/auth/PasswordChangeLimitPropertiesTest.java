package com.jey.core.auth;

import java.time.Duration;

import com.jey.core.auth.api.RateLimit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PasswordChangeLimitPropertiesTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

	@Test
	void 설정이_없으면_15분에_다섯_번이다() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			PasswordChangeLimitProperties properties = context.getBean(PasswordChangeLimitProperties.class);
			assertThat(properties.window()).isEqualTo(Duration.ofMinutes(15));
			assertThat(properties.maxFailures()).isEqualTo(5);
		});
	}

	@Test
	void 설정_키_이름대로_값을_읽는다() {
		runner
			.withPropertyValues("jey.auth.password-change-limit.window=30m",
					"jey.auth.password-change-limit.max-failures=3")
			.run(context -> {
				PasswordChangeLimitProperties properties = context.getBean(PasswordChangeLimitProperties.class);
				assertThat(properties.window()).isEqualTo(Duration.ofMinutes(30));
				assertThat(properties.maxFailures()).isEqualTo(3);
			});
	}

	// 단위를 빼먹은 "15"는 15밀리초로 읽힌다. 그런 값으로 뜨면 제한이 사실상 꺼진다.
	@Test
	void 단위를_빼먹은_구간으로는_서버가_뜨지_않는다() {
		runner.withPropertyValues("jey.auth.password-change-limit.window=15")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("jey.auth.password-change-limit.window"));
	}

	// 0이면 아무도 비밀번호를 바꿀 수 없다.
	@Test
	void 횟수가_1보다_작으면_서버가_뜨지_않는다() {
		runner.withPropertyValues("jey.auth.password-change-limit.max-failures=0")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("jey.auth.password-change-limit.max-failures"));
	}

	@Test
	void 구간은_1분부터_받는다() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new PasswordChangeLimitProperties(Duration.ofSeconds(59), 5));
		assertThatIllegalArgumentException().isThrownBy(() -> new PasswordChangeLimitProperties(null, 5));
		assertThat(new PasswordChangeLimitProperties(Duration.ofMinutes(1), 1).maxFailures()).isEqualTo(1);
	}

	// 이름은 저장소의 키에 들어간다. 바뀌면 그때까지 센 횟수를 찾지 못한다.
	@Test
	void 한도의_이름과_값을_그대로_넘긴다() {
		RateLimit limit = new PasswordChangeLimitProperties(Duration.ofMinutes(15), 5).toRateLimit();

		assertThat(limit.name()).isEqualTo("auth.password-change");
		assertThat(limit.max()).isEqualTo(5);
		assertThat(limit.window()).isEqualTo(Duration.ofMinutes(15));
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(PasswordChangeLimitProperties.class)
	static class Config {

	}

}
