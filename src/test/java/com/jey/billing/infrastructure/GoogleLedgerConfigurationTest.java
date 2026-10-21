package com.jey.billing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.jey.billing.application.LedgerSheets;

// 키가 없으면 장부 연결이 꺼진 채로 뜨고, 키가 있는데 깨졌으면 서버가 뜨지 않는다. Docker가 필요 없다.
class GoogleLedgerConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(GoogleLedgerConfiguration.class);

	@Test
	void 키가_없으면_장부_연결이_꺼진_채로_뜬다() {
		runner.run(context -> {
			assertThat(context).hasNotFailed().hasSingleBean(LedgerSheets.class);
			assertThat(context.getBean(GoogleAccessTokens.class)).isNotInstanceOf(ServiceAccountAccessTokens.class);
		});
	}

	@Test
	void 키가_있는데_깨졌으면_서버가_뜨지_않는다() {
		runner.withPropertyValues("jey.billing.ledger.service-account-key=bm90LWEta2V5")
			.run(context -> assertThat(context).hasFailed());
	}

}
