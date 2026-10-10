package com.jey.core.auth;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.jey.core.auth.api.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// AuthPropertiesTest는 객체를 직접 만들어서 본다. 여기서는 Spring Boot가 설정값을 읽어 객체를 만드는 과정을 거친다.
// 설정 키 이름, 기본값, 잘못된 값으로는 서버가 뜨지 않는다는 것을 확인한다. 스프링 컨텍스트만 띄우므로 Docker가 필요 없다.
class AuthPropertiesBindingTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

	// Secure 쿠키는 설정을 빠뜨려도 켜져 있어야 한다.
	@Test
	void 설정이_없으면_안전한_기본값으로_뜬다() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			AuthProperties properties = context.getBean(AuthProperties.class);
			assertThat(properties.secureCookies()).isTrue();
			assertThat(properties.allowedOrigins()).isEmpty();
			assertThat(properties.allowedOriginsRequired()).isFalse();
			assertThat(properties.initialAdmin().required()).isFalse();
			assertThat(properties.initialAdmin().isConfigured()).isFalse();
		});
	}

	@Test
	void 설정_키_이름대로_값을_읽는다() {
		runner.withPropertyValues(
				"jey.auth.allowed-origins=https://a.example, https://b.example",
				"jey.auth.cookie-domain=jey.example",
				"jey.auth.secure-cookies=false",
				"jey.auth.session-timeout.staff=12h",
				"jey.auth.session-max-lifetime.staff=16h",
				"jey.auth.session-max-lifetime.partner=150m",
				"jey.auth.initial-admin.login-id=owner",
				"jey.auth.initial-admin.password=first-admin-password",
				"jey.auth.initial-admin.name=박원장",
				"jey.auth.initial-admin.required=true")
				.run(context -> {
					assertThat(context).hasNotFailed();
					AuthProperties properties = context.getBean(AuthProperties.class);
					assertThat(properties.allowedOrigins()).containsExactly("https://a.example", "https://b.example");
					assertThat(properties.cookieDomain()).isEqualTo("jey.example");
					assertThat(properties.secureCookies()).isFalse();
					assertThat(properties.sessionTimeoutFor(UserRole.STAFF)).isEqualTo(Duration.ofHours(12));
					assertThat(properties.sessionMaxLifetimeFor(UserRole.STAFF)).isEqualTo(Duration.ofHours(16));
					assertThat(properties.sessionMaxLifetimeFor(UserRole.PARTNER)).isEqualTo(Duration.ofMinutes(150));
					assertThat(properties.initialAdmin().isConfigured()).isTrue();
					assertThat(properties.initialAdmin().name()).isEqualTo("박원장");
					assertThat(properties.initialAdmin().required()).isTrue();
				});
	}

	// 운영에서 환경변수가 비어 있으면 이런 값이 들어온다. 아이디와 비밀번호가 둘 다 비면 "설정 없음"이고 "반쪽 설정"이 아니다.
	@Test
	void 빈_환경변수로_들어온_첫_관리자_설정은_없는_것으로_본다() {
		runner.withPropertyValues(
				"jey.auth.initial-admin.login-id=",
				"jey.auth.initial-admin.password=",
				"jey.auth.initial-admin.required=true")
				.run(context -> {
					AuthProperties properties = context.getBean(AuthProperties.class);
					assertThat(properties.initialAdmin().isConfigured()).isFalse();
					assertThat(properties.initialAdmin().isHalfConfigured()).isFalse();
					assertThat(properties.initialAdmin().required()).isTrue();
				});
	}

	@Test
	void 허용_출처의_환경변수가_없으면_뜨지_않는다() {
		runner.withPropertyValues("jey.auth.allowed-origins=${AUTH_ALLOWED_ORIGINS_NOT_SET}")
				.run(context -> assertThat(context).hasFailed().getFailure().rootCause()
						.hasMessageContaining("allowed-origins"));
	}

	// 환경변수가 "있지만 비어 있는" 경우다. 운영 설정은 이것도 막는다.
	@Test
	void 허용_출처가_필수인데_비어_있으면_뜨지_않는다() {
		runner.withPropertyValues("jey.auth.allowed-origins=", "jey.auth.allowed-origins-required=true")
				.run(context -> assertThat(context).hasFailed().getFailure().rootCause()
						.hasMessageContaining("allowed-origins"));
	}

	// 단위를 빼먹은 "12"는 12밀리초로 읽힌다.
	@Test
	void 세션_시간에_단위를_빼먹으면_뜨지_않는다() {
		runner.withPropertyValues("jey.auth.session-timeout.staff=12")
				.run(context -> assertThat(context).hasFailed().getFailure().rootCause()
						.hasMessageContaining("session-timeout.staff"));
	}

	@Test
	void 없는_역할_이름을_쓰면_뜨지_않는다() {
		runner.withPropertyValues("jey.auth.session-timeout.manager=1h")
				.run(context -> assertThat(context).hasFailed());
	}

	// 운영 설정 파일의 키 이름에 오타가 있으면 그 설정은 조용히 무시된다. 파일을 직접 읽어서 확인한다.
	@Test
	void 운영_설정_파일은_Secure_쿠키와_필수_검사를_켠다() throws Exception {
		AuthProperties properties = bindProdYaml(
				Map.of("AUTH_ALLOWED_ORIGINS", "https://app.jey.example", "AUTH_COOKIE_DOMAIN", "jey.example"));

		assertThat(properties.allowedOrigins()).containsExactly("https://app.jey.example");
		assertThat(properties.allowedOriginsRequired()).isTrue();
		assertThat(properties.cookieDomain()).isEqualTo("jey.example");
		assertThat(properties.secureCookies()).isTrue();
		assertThat(properties.initialAdmin().required()).isTrue();
		assertThat(properties.initialAdmin().isConfigured()).isFalse();
	}

	@Test
	void 운영_설정_파일은_허용_출처가_없거나_비면_읽히지_않는다() {
		assertThatThrownBy(() -> bindProdYaml(Map.of()))
				.rootCause().hasMessageContaining("allowed-origins");
		assertThatThrownBy(() -> bindProdYaml(Map.of("AUTH_ALLOWED_ORIGINS", "")))
				.rootCause().hasMessageContaining("allowed-origins");
	}

	// application-prod.yaml을 읽고, 환경변수 자리는 주어진 값으로 채워서 AuthProperties를 만든다.
	private static AuthProperties bindProdYaml(Map<String, Object> environmentVariables) throws Exception {
		List<PropertySource<?>> yaml = new YamlPropertySourceLoader().load("prod",
				new ClassPathResource("application-prod.yaml"));
		// 이 PC의 실제 환경변수가 섞이지 않게 빈 환경에서 시작한다.
		var environment = new MockEnvironment();
		environment.getPropertySources().addFirst(new MapPropertySource("test-env", environmentVariables));
		yaml.forEach(environment.getPropertySources()::addLast);
		ConfigurationPropertySources.attach(environment);
		return Binder.get(environment).bind("jey.auth", AuthProperties.class).get();
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(AuthProperties.class)
	static class Config {
	}

}
