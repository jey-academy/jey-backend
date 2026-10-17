package com.jey.dongtanpass.application;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

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

// 신규 학생의 기간이 빠지거나 잘못되면 다니던 학생에게 패스를 쓸 수 있게 된다. 그런 값으로는 서버가 뜨지 않아야 한다. Docker가 필요 없다.
class RedeemPropertiesTest {

	private static final Duration SEVEN_DAYS = Duration.ofDays(7);

	private static final Instant CREATED = Instant.parse("2026-10-10T01:00:00Z");

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

	// 만든 시각부터 7일이 지나기 전까지 신규다(ADR-0033). 7일째 되는 그 시각부터는 아니다.
	@Test
	void 만든_시각부터_기간이_지나기_전까지_신규다() {
		RedeemProperties properties = new RedeemProperties(SEVEN_DAYS);

		assertThat(properties.isNew(CREATED, CREATED)).isTrue();
		assertThat(properties.isNew(CREATED, CREATED.plus(SEVEN_DAYS).minusNanos(1000))).isTrue();
		assertThat(properties.isNew(CREATED, CREATED.plus(SEVEN_DAYS))).isFalse();
		assertThat(properties.isNew(CREATED, CREATED.plus(Duration.ofDays(8)))).isFalse();
	}

	// 서버끼리 시계가 조금 어긋나면 방금 만든 학생이 "미래에 만든" 것으로 보일 수 있다. 막지 않는다.
	@Test
	void 만든_시각이_지금보다_뒤여도_신규다() {
		assertThat(new RedeemProperties(SEVEN_DAYS).isNew(CREATED, CREATED.minusSeconds(1))).isTrue();
	}

	@Test
	void 기간은_1일_이상_31일_이하다() {
		assertThat(new RedeemProperties(Duration.ofDays(1)).newStudentWindow()).isEqualTo(Duration.ofDays(1));
		assertThat(new RedeemProperties(Duration.ofDays(31)).newStudentWindow()).isEqualTo(Duration.ofDays(31));
		// 단위를 빼먹은 "7"은 7밀리초로 읽힌다. 그런 값이면 아무 학생도 신규가 아니다.
		assertThatThrownBy(() -> new RedeemProperties(Duration.ofMillis(7)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("jey.dongtanpass.redeem.new-student-window");
		assertThatThrownBy(() -> new RedeemProperties(Duration.ofDays(32))).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("jey.dongtanpass.redeem.new-student-window");
		assertThatThrownBy(() -> new RedeemProperties(null)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("jey.dongtanpass.redeem.new-student-window");
	}

	@Test
	void 설정_키_이름대로_값을_읽는다() {
		runner.withPropertyValues("jey.dongtanpass.redeem.new-student-window=3d").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(RedeemProperties.class).newStudentWindow()).isEqualTo(Duration.ofDays(3));
		});
	}

	@Test
	void 설정이_없거나_잘못되면_서버가_뜨지_않는다() {
		runner.run(context -> assertThat(context).hasFailed());
		runner.withPropertyValues("jey.dongtanpass.redeem.new-student-window=40d")
			.run(context -> assertThat(context).hasFailed());
	}

	// 테스트도 이 값을 그대로 쓴다. 운영이 쓰는 기본 설정 파일을 직접 읽어서 본다.
	@Test
	void 운영_설정의_기간은_7일이다() throws IOException {
		StandardEnvironment environment = new StandardEnvironment();
		new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))
			.forEach(environment.getPropertySources()::addLast);

		RedeemProperties production = Binder.get(environment)
			.bind("jey.dongtanpass.redeem", RedeemProperties.class)
			.get();

		assertThat(production.newStudentWindow()).isEqualTo(SEVEN_DAYS);
	}

	@Configuration
	@EnableConfigurationProperties(RedeemProperties.class)
	static class Config {

	}

}
