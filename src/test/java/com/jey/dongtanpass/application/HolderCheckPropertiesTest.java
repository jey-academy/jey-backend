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

// 한도 설정이 빠지거나 잘못되면 본인 미리 확인의 번호 대입 방지가 조용히 꺼진다. 그런 값으로는 서버가 뜨지 않아야 한다. Docker가 필요 없다.
class HolderCheckPropertiesTest {

	private static final String[] ALL = { "jey.dongtanpass.holder-check.window=1h",
			"jey.dongtanpass.holder-check.max=30" };

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

	// 다른 한도의 이름을 집으면 규칙 테스트로는 잡히지 않는다(그 이름도 선언돼 있다).
	@Test
	void 한도는_본인_미리_확인의_이름과_횟수를_쓴다() {
		assertThat(new HolderCheckProperties(30, Duration.ofHours(1)).limit())
			.isEqualTo(new RateLimit("dongtanpass.holder-check", 30, Duration.ofHours(1)));
	}

	// 경계값은 받는다(대조군).
	@Test
	void 구간_1분과_횟수_1은_받는다() {
		assertThat(new HolderCheckProperties(1, Duration.ofMinutes(1)).limit())
			.isEqualTo(new RateLimit("dongtanpass.holder-check", 1, Duration.ofMinutes(1)));
	}

	@Test
	void 구간은_1분_이상이고_횟수는_1_이상이다() {
		// 단위를 빼먹은 "1"은 1밀리초로 읽힌다.
		assertThatThrownBy(() -> new HolderCheckProperties(30, Duration.ofMillis(1)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("holder-check.window");
		assertThatThrownBy(() -> new HolderCheckProperties(30, null)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("holder-check.window");
		assertThatThrownBy(() -> new HolderCheckProperties(0, Duration.ofHours(1)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("holder-check.max");
	}

	// 30일 상한은 한도(RateLimit)를 만들 때 걸린다. 뜰 때 막는다.
	@Test
	void 한도로_쓸_수_없는_구간이면_서버가_뜨지_않는다() {
		assertThatThrownBy(() -> new HolderCheckProperties(30, Duration.ofDays(31)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("jey.dongtanpass.holder-check");
		runner.withPropertyValues(ALL)
			.withPropertyValues("jey.dongtanpass.holder-check.window=31d")
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void 설정_키_이름대로_값을_읽는다() {
		runner.withPropertyValues(ALL).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(HolderCheckProperties.class).limit())
				.isEqualTo(new RateLimit("dongtanpass.holder-check", 30, Duration.ofHours(1)));
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

		HolderCheckProperties production = Binder.get(environment)
			.bind("jey.dongtanpass.holder-check", HolderCheckProperties.class)
			.get();

		assertThat(production.limit()).isEqualTo(new RateLimit("dongtanpass.holder-check", 30, Duration.ofHours(1)));
	}

	@Configuration
	@EnableConfigurationProperties(HolderCheckProperties.class)
	static class Config {

	}

}
