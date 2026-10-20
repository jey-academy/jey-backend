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
		runner.withPropertyValues("jey.billing.discount-policy=SEQUENTIAL",
				"jey.billing.referenced-discount-codes=DONGTAN_PASS,COUPON").run(context -> {
					assertThat(context).hasNotFailed();
					BillingProperties properties = context.getBean(BillingProperties.class);
					assertThat(properties.discountPolicy()).isEqualTo(DiscountPolicy.SEQUENTIAL);
					assertThat(properties.needsReference("COUPON")).isTrue();
					assertThat(properties.needsReference("SIBLING_5")).isFalse();
				});
	}

	@Test
	void 설정이_없거나_모르는_값이면_서버가_뜨지_않는다() {
		runner.run(context -> assertThat(context).hasFailed());
		runner.withPropertyValues("jey.billing.discount-policy=MAX_ONLY",
				"jey.billing.referenced-discount-codes=DONGTAN_PASS").run(context -> assertThat(context).hasFailed());
		// 근거가 필요한 할인 목록이 빠지면 패스 할인을 아무 청구에나 고를 수 있게 된다. 조용히 빈 목록이 되지 않는다.
		runner.withPropertyValues("jey.billing.discount-policy=ADDITIVE").run(context -> assertThat(context).hasFailed());
	}

	// 원장님 확인 전 기본값은 합산이다. 운영이 쓰는 기본 설정 파일을 직접 읽어서 본다.
	@Test
	void 운영_설정은_합산이고_패스_할인은_근거가_필요하다() throws IOException {
		StandardEnvironment environment = new StandardEnvironment();
		new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))
			.forEach(environment.getPropertySources()::addLast);

		BillingProperties production = Binder.get(environment).bind("jey.billing", BillingProperties.class).get();

		assertThat(production.discountPolicy()).isEqualTo(DiscountPolicy.ADDITIVE);
		assertThat(production.referencedDiscountCodes()).containsExactly("DONGTAN_PASS");
	}

	@Configuration
	@EnableConfigurationProperties(BillingProperties.class)
	static class Config {

	}

}
