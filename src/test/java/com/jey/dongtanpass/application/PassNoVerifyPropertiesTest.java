package com.jey.dongtanpass.application;

import java.io.IOException;
import java.time.Duration;

import com.jey.core.auth.api.RateLimit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 한도 설정이 빠지거나 잘못되면 패스 번호 대입 방지가 조용히 꺼진다. 그런 값으로는 서버가 뜨지 않아야 한다. Docker가 필요 없다.
class PassNoVerifyPropertiesTest {

	private static final String[] ALL = { "jey.dongtanpass.pass-no-verify.window=1h",
			"jey.dongtanpass.pass-no-verify.max=60" };

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

	// 다른 한도의 이름을 집으면 규칙 테스트로는 잡히지 않는다(그 이름도 선언돼 있다).
	@Test
	void 한도는_패스_번호_확인의_이름과_횟수를_쓴다() {
		assertThat(new PassNoVerifyProperties(60, Duration.ofHours(1)).limit())
			.isEqualTo(new RateLimit("dongtanpass.pass-no-verify", 60, Duration.ofHours(1)));
	}

	@Test
	void 구간은_1분_이상이고_횟수는_1_이상이다() {
		// 단위를 빼먹은 "1"은 1밀리초로 읽힌다.
		assertThatThrownBy(() -> new PassNoVerifyProperties(60, Duration.ofMillis(1)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("pass-no-verify.window");
		assertThatThrownBy(() -> new PassNoVerifyProperties(60, null)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("pass-no-verify.window");
		assertThatThrownBy(() -> new PassNoVerifyProperties(0, Duration.ofHours(1)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("pass-no-verify.max");
	}

	// 30일 상한은 한도(RateLimit)를 만들 때 걸린다. 뜰 때 막는다.
	@Test
	void 한도로_쓸_수_없는_구간이면_서버가_뜨지_않는다() {
		assertThatThrownBy(() -> new PassNoVerifyProperties(60, Duration.ofDays(31)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("jey.dongtanpass.pass-no-verify");
		runner.withPropertyValues(ALL)
			.withPropertyValues("jey.dongtanpass.pass-no-verify.window=31d")
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void 설정_키_이름대로_값을_읽는다() {
		runner.withPropertyValues(ALL).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(PassNoVerifyProperties.class).limit())
				.isEqualTo(new RateLimit("dongtanpass.pass-no-verify", 60, Duration.ofHours(1)));
		});
	}

	@Test
	void 설정이_없으면_서버가_뜨지_않는다() {
		runner.run(context -> assertThat(context).hasFailed());
	}

	// 테스트도 이 값을 그대로 쓴다. 운영이 쓰는 기본 설정 파일을 직접 읽어서 본다.
	@Test
	void 운영_설정의_한도는_정한_값이다() throws IOException {
		StandardEnvironment environment = new StandardEnvironment();
		new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))
			.forEach(environment.getPropertySources()::addLast);

		PassNoVerifyProperties production = Binder.get(environment)
			.bind("jey.dongtanpass.pass-no-verify", PassNoVerifyProperties.class)
			.get();

		assertThat(production.limit()).isEqualTo(new RateLimit("dongtanpass.pass-no-verify", 60, Duration.ofHours(1)));
	}

	@Configuration
	@EnableConfigurationProperties(PassNoVerifyProperties.class)
	static class Config {

	}

}
