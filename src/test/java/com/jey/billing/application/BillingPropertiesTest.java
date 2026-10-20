package com.jey.billing.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import com.jey.billing.domain.DiscountPolicy;

// 할인 방식이 빠지거나 틀리면 금액이 달라진다. 그런 값으로는 서버가 뜨지 않아야 한다. Docker가 필요 없다.
class BillingPropertiesTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

	@Test
	void 설정_키_이름대로_값을_읽는다() {
		runner.withPropertyValues("jey.billing.discount-policy=SEQUENTIAL").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(BillingProperties.class).discountPolicy()).isEqualTo(DiscountPolicy.SEQUENTIAL);
		});
	}

	@Test
	void 설정이_없거나_모르는_값이면_서버가_뜨지_않는다() {
		runner.run(context -> assertThat(context).hasFailed());
		runner.withPropertyValues("jey.billing.discount-policy=MAX_ONLY").run(context -> assertThat(context).hasFailed());
	}

	// 원장님 확인 전 기본값은 합산이다. 운영이 쓰는 기본 설정 파일을 직접 읽어서 본다.
	@Test
	void 운영_설정은_합산이다() throws IOException {
		StandardEnvironment environment = new StandardEnvironment();
		new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))
			.forEach(environment.getPropertySources()::addLast);

		BillingProperties production = Binder.get(environment).bind("jey.billing", BillingProperties.class).get();

		assertThat(production.discountPolicy()).isEqualTo(DiscountPolicy.ADDITIVE);
	}

	@Configuration
	@EnableConfigurationProperties(BillingProperties.class)
	static class Config {

	}

}
