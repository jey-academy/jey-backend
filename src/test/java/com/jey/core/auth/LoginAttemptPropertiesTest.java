package com.jey.core.auth;

import java.time.Duration;
import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class LoginAttemptPropertiesTest {

	private static final String HMAC_KEY = "test-only-login-attempt-hmac-key-0123456789";

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

	// 비밀키만은 기본값이 없다.
	@Test
	void 비밀키_말고는_설정이_없으면_기본값을_쓴다() {
		runner.withPropertyValues("jey.auth.login-attempt-limit.hmac-key=" + HMAC_KEY).run(context -> {
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
				"jey.auth.login-attempt-limit.hmac-key=" + HMAC_KEY,
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
		runner.withPropertyValues("jey.auth.login-attempt-limit.hmac-key=" + HMAC_KEY,
				"jey.auth.login-attempt-limit.window=15")
				.run(context -> assertThat(context).hasFailed().getFailure().rootCause()
						.hasMessageContaining("login-attempt-limit.window"));
	}

	@Test
	void 비밀키가_없으면_뜨지_않는다() {
		runner.run(context -> assertThat(context).hasFailed().getFailure().rootCause()
				.hasMessageContaining("login-attempt-limit.hmac-key"));
		runner.withPropertyValues("jey.auth.login-attempt-limit.hmac-key=")
				.run(context -> assertThat(context).hasFailed());
	}

	// 운영 설정은 환경변수로 받는다. 환경변수를 빠뜨리면 "${...}"라는 글자가 그대로 키가 된다.
	@Test
	void 비밀키의_환경변수가_없으면_뜨지_않는다() {
		runner.withPropertyValues("jey.auth.login-attempt-limit.hmac-key=${AUTH_LOGIN_ATTEMPT_HMAC_KEY_NOT_SET}")
				.run(context -> assertThat(context).hasFailed());
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new LoginAttemptProperties(Duration.ofMinutes(15), 5, 30,
						"${AUTH_LOGIN_ATTEMPT_HMAC_KEY}-and-some-more-text"))
				.withMessageContaining("환경변수");
	}

	@Test
	void 비밀키는_32자_이상이어야_하고_오류와_문자열_표현에_값이_드러나지_않는다() {
		String shortKey = "only-31-characters-long-key-xxx";
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new LoginAttemptProperties(Duration.ofMinutes(15), 5, 30, shortKey))
				.withMessageContaining("hmac-key")
				.withMessageNotContaining(shortKey);

		assertThat(new LoginAttemptProperties(Duration.ofMinutes(15), 5, 30, HMAC_KEY).toString())
				.contains("window=PT15M").doesNotContain(HMAC_KEY);
	}

	@Test
	void 운영_설정은_비밀키를_환경변수로_받는다() throws Exception {
		assertThat(yamlProperty("application-prod.yaml", "jey.auth.login-attempt-limit.hmac-key"))
				.isEqualTo("${AUTH_LOGIN_ATTEMPT_HMAC_KEY}");
		assertThat(yamlProperty("application.yaml", "jey.auth.login-attempt-limit.hmac-key")).isNull();
	}

	@Test
	void 한도는_1_이상이어야_한다() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new LoginAttemptProperties(Duration.ofMinutes(15), 0, 30, HMAC_KEY))
				.withMessageContaining("max-failures-per-login-id-and-ip");
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new LoginAttemptProperties(Duration.ofMinutes(15), -1, 30, HMAC_KEY))
				.withMessageContaining("max-failures-per-login-id-and-ip");
	}

	@Test
	void 기간은_1분_이상이어야_한다() {
		assertThat(new LoginAttemptProperties(Duration.ofMinutes(1), 5, 30, HMAC_KEY).window()).isEqualTo(Duration.ofMinutes(1));
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new LoginAttemptProperties(Duration.ofSeconds(59), 5, 30, HMAC_KEY))
				.withMessageContaining("window");
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new LoginAttemptProperties(null, 5, 30, HMAC_KEY))
				.withMessageContaining("window");
	}

	// 한 계정의 한도가 IP 전체의 한도보다 크면 IP 한도가 먼저 걸려 계정 한도가 의미가 없다. 둘을 바꿔 적은 실수일 가능성이 크다.
	// 아이디별 한도가 1 이상이므로 IP 한도 0도 여기서 걸린다.
	@Test
	void IP_한도는_아이디별_한도보다_작을_수_없다() {
		assertThat(new LoginAttemptProperties(Duration.ofMinutes(15), 5, 5, HMAC_KEY).maxFailuresPerIp()).isEqualTo(5);
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new LoginAttemptProperties(Duration.ofMinutes(15), 10, 5, HMAC_KEY))
				.withMessageContaining("max-failures-per-ip");
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new LoginAttemptProperties(Duration.ofMinutes(15), 5, 0, HMAC_KEY))
				.withMessageContaining("max-failures-per-ip");
	}

	// 시도 횟수는 요청이 온 주소로 센다. 프록시가 없는 환경에서 전달 헤더를 믿으면 헤더만 바꿔서 한도를 피할 수 있다.
	@Test
	void 전달_헤더는_운영_설정에서만_믿는다() throws Exception {
		assertThat(yamlProperty("application.yaml", "server.forward-headers-strategy")).isNull();
		assertThat(yamlProperty("application-local.yaml", "server.forward-headers-strategy")).isNull();
		assertThat(yamlProperty("application-test.yaml", "server.forward-headers-strategy")).isNull();
		assertThat(yamlProperty("application-prod.yaml", "server.forward-headers-strategy")).isEqualTo("native");
	}

	private static Object yamlProperty(String file, String name) throws Exception {
		return new YamlPropertySourceLoader().load(file, new ClassPathResource(file)).stream()
				.map(source -> source.getProperty(name))
				.filter(Objects::nonNull)
				.findFirst().orElse(null);
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(LoginAttemptProperties.class)
	static class Config {
	}

}
