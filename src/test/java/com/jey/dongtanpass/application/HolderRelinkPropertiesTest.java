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

// 한도 설정이 빠지거나 잘못되면 공개 경로의 대입 방지가 조용히 꺼진다. 그런 값으로는 서버가 뜨지 않아야 한다. Docker가 필요 없다.
class HolderRelinkPropertiesTest {

	private static final String[] ALL = { "jey.dongtanpass.holder-relink.window=5m",
			"jey.dongtanpass.holder-relink.max-failures-per-phone-and-client=10",
			"jey.dongtanpass.holder-relink.max-failures-per-client=60" };

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

	// 두 한도가 서로의 이름을 집으면 규칙 테스트로는 잡히지 않는다(이름은 둘 다 선언돼 있다).
	@Test
	void 두_한도는_각자의_이름과_횟수를_쓴다() {
		HolderRelinkProperties properties = new HolderRelinkProperties(10, 60, Duration.ofMinutes(5));

		assertThat(properties.perPhoneAndClient())
			.isEqualTo(new RateLimit("dongtanpass.holder-relink", 10, Duration.ofMinutes(5)));
		assertThat(properties.perClient())
			.isEqualTo(new RateLimit("dongtanpass.holder-relink-client", 60, Duration.ofMinutes(5)));
	}

	@Test
	void 구간은_1분_이상이고_한도는_1_이상이다() {
		// 단위를 빼먹은 "5"는 5밀리초로 읽힌다.
		assertThatThrownBy(() -> new HolderRelinkProperties(10, 60, Duration.ofMillis(5)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("holder-relink.window");
		assertThatThrownBy(() -> new HolderRelinkProperties(10, 60, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("holder-relink.window");
		assertThatThrownBy(() -> new HolderRelinkProperties(0, 60, Duration.ofMinutes(5)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("max-failures-per-phone-and-client");
		assertThatThrownBy(() -> new HolderRelinkProperties(10, 0, Duration.ofMinutes(5)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("max-failures-per-client");
	}

	// 30일 상한은 한도(RateLimit)를 만들 때 걸린다. 요청마다 만들면 서버는 뜨고 다시 받기만 500이 된다. 뜰 때 막는다.
	@Test
	void 한도로_쓸_수_없는_구간이면_서버가_뜨지_않는다() {
		assertThatThrownBy(() -> new HolderRelinkProperties(10, 60, Duration.ofDays(31)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("jey.dongtanpass.holder-relink");
		runner.withPropertyValues(ALL)
			.withPropertyValues("jey.dongtanpass.holder-relink.window=31d")
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void 설정_키_이름대로_값을_읽는다() {
		runner.withPropertyValues(ALL).run(context -> {
			assertThat(context).hasNotFailed();
			HolderRelinkProperties properties = context.getBean(HolderRelinkProperties.class);
			assertThat(properties.perPhoneAndClient().max()).isEqualTo(10);
			assertThat(properties.perClient().max()).isEqualTo(60);
			assertThat(properties.perClient().window()).isEqualTo(Duration.ofMinutes(5));
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

		HolderRelinkProperties production = Binder.get(environment)
			.bind("jey.dongtanpass.holder-relink", HolderRelinkProperties.class)
			.get();

		assertThat(production.perPhoneAndClient())
			.isEqualTo(new RateLimit("dongtanpass.holder-relink", 10, Duration.ofMinutes(5)));
		assertThat(production.perClient())
			.isEqualTo(new RateLimit("dongtanpass.holder-relink-client", 60, Duration.ofMinutes(5)));
	}

	@Configuration
	@EnableConfigurationProperties(HolderRelinkProperties.class)
	static class Config {

	}

}
