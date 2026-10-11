package com.jey.core.member;

import java.io.IOException;
import java.time.Duration;

import com.jey.core.auth.api.RateLimit;
import com.jey.core.member.MemberLimitProperties.Limit;
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

// 한도 설정이 빠지거나 잘못되면 제한이 조용히 꺼진다. 그런 값으로는 서버가 뜨지 않아야 한다. Docker가 필요 없다.
class MemberLimitPropertiesTest {

	private static final Limit FINE = new Limit(Duration.ofHours(1), 60);

	private static final String[] ALL = { "jey.member.phone-reveal-limit.window=1h",
			"jey.member.phone-reveal-limit.max=60", "jey.member.phone-change-limit.window=1h",
			"jey.member.phone-change-limit.max=100", "jey.member.phone-probe-limit.window=15m",
			"jey.member.phone-probe-limit.max=10" };

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

	@Test
	void 한도_셋이_모두_있어야_한다() {
		assertThatThrownBy(() -> new MemberLimitProperties(null, FINE, FINE))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("phone-reveal-limit");
		assertThatThrownBy(() -> new MemberLimitProperties(FINE, null, FINE))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("phone-change-limit");
		assertThatThrownBy(() -> new MemberLimitProperties(FINE, FINE, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("phone-probe-limit");
	}

	@Test
	void 구간은_1분_이상이고_한도는_1_이상이다() {
		// 단위를 빼먹은 "5"는 5밀리초로 읽힌다.
		assertThatThrownBy(() -> new MemberLimitProperties(new Limit(Duration.ofMillis(5), 60), FINE, FINE))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("phone-reveal-limit.window");
		assertThatThrownBy(() -> new MemberLimitProperties(FINE, new Limit(null, 60), FINE))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("phone-change-limit.window");
		assertThatThrownBy(() -> new MemberLimitProperties(FINE, FINE, new Limit(Duration.ofHours(1), 0)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("phone-probe-limit.max");
	}

	@Test
	void 설정_키_이름대로_값을_읽는다() {
		runner.withPropertyValues(ALL).run(context -> {
			assertThat(context).hasNotFailed();
			MemberLimitProperties limits = context.getBean(MemberLimitProperties.class);
			assertThat(limits.phoneReveal()).isEqualTo(new RateLimit("member.phone-reveal", 60, Duration.ofHours(1)));
			assertThat(limits.phoneChange()).isEqualTo(new RateLimit("member.phone-change", 100, Duration.ofHours(1)));
			assertThat(limits.phoneProbe()).isEqualTo(new RateLimit("member.phone-probe", 10, Duration.ofMinutes(15)));
		});
	}

	@Test
	void 설정이_통째로_없으면_서버가_뜨지_않는다() {
		runner.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void 한도_하나가_빠지면_서버가_뜨지_않는다() {
		runner.withPropertyValues("jey.member.phone-reveal-limit.window=1h", "jey.member.phone-reveal-limit.max=60",
				"jey.member.phone-probe-limit.window=15m", "jey.member.phone-probe-limit.max=10")
				.run(context -> assertThat(context).hasFailed()
						.getFailure()
						.rootCause()
						.hasMessageContaining("phone-change-limit"));
	}

	@Test
	void 단위를_빼먹은_구간으로는_서버가_뜨지_않는다() {
		runner.withPropertyValues(ALL)
				.withPropertyValues("jey.member.phone-probe-limit.window=15")
				.run(context -> assertThat(context).hasFailed()
						.getFailure()
						.rootCause()
						.hasMessageContaining("phone-probe-limit.window"));
	}

	// 테스트 프로필은 한도를 바꿔 둔다. 운영이 쓰는 기본 설정 파일을 직접 읽어서 본다.
	@Test
	void 운영_설정의_한도는_정한_값이다() throws IOException {
		StandardEnvironment environment = new StandardEnvironment();
		new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))
				.forEach(environment.getPropertySources()::addLast);

		MemberLimitProperties production = Binder.get(environment)
				.bind("jey.member", MemberLimitProperties.class)
				.get();

		assertThat(production.phoneReveal()).isEqualTo(new RateLimit("member.phone-reveal", 60, Duration.ofHours(1)));
		assertThat(production.phoneChange()).isEqualTo(new RateLimit("member.phone-change", 100, Duration.ofHours(1)));
		assertThat(production.phoneProbe()).isEqualTo(new RateLimit("member.phone-probe", 10, Duration.ofMinutes(15)));
	}

	@Configuration
	@EnableConfigurationProperties(MemberLimitProperties.class)
	static class Config {

	}

}
