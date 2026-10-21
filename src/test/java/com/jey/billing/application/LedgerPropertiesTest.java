package com.jey.billing.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

// 장부 연결 설정. 지점마다 스프레드시트 하나이고, 켰는데 키가 없는 실수는 서버가 뜨지 않게 막는다. Docker가 필요 없다.
class LedgerPropertiesTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

	@Test
	void 아무것도_정하지_않으면_장부_연결이_꺼지고_구글_주소와_시간_제한은_기본값이다() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			LedgerProperties properties = context.getBean(LedgerProperties.class);
			assertThat(properties.spreadsheetFor(1)).isEmpty();
			assertThat(properties.baseUrl()).isEqualTo("https://sheets.googleapis.com");
			assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
			assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(10));
		});
	}

	@Test
	void 운영의_환경_변수_이름으로_지점의_스프레드시트를_정한다() {
		runner
			// 실제 환경 변수 출처와 같은 이름이어야 환경 변수 이름 규칙(_ → .)이 적용된다.
			.withInitializer(context -> context.getEnvironment().getPropertySources()
				.replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
						new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
						Map.of("JEY_BILLING_LEDGER_SPREADSHEETS_1", "1AbC-dEf_123",
								"JEY_BILLING_LEDGER_SERVICE_ACCOUNT_KEY", "a2V5"))))
			.run(context -> {
				assertThat(context).hasNotFailed();
				LedgerProperties properties = context.getBean(LedgerProperties.class);
				assertThat(properties.spreadsheetFor(1)).contains("1AbC-dEf_123");
				assertThat(properties.spreadsheetFor(2)).isEmpty();
			});
	}

	@Test
	void 스프레드시트를_정했는데_키가_없으면_서버가_뜨지_않는다() {
		runner.withPropertyValues("jey.billing.ledger.spreadsheets.1=1AbC").run(context -> assertThat(context).hasFailed());
		runner.withPropertyValues("jey.billing.ledger.spreadsheets.1=1AbC", "jey.billing.ledger.service-account-key= ")
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void 스프레드시트_ID의_모양이_틀리면_서버가_뜨지_않는다() {
		runner.withPropertyValues("jey.billing.ledger.spreadsheets.1=https://docs.google.com/x",
				"jey.billing.ledger.service-account-key=a2V5").run(context -> assertThat(context).hasFailed());
	}

	@Test
	void 시간_제한이_너무_길면_서버가_뜨지_않는다_리스너가_한_주기보다_훨씬_빨리_끝나야_한다() {
		runner.withPropertyValues("jey.billing.ledger.read-timeout=60s").run(context -> assertThat(context).hasFailed());
	}

	@Test
	void 다시_시도_간격은_기본_1분이고_10초보다_짧으면_서버가_뜨지_않는다() {
		runner.run(context -> assertThat(context.getBean(LedgerProperties.class).retryInterval())
			.isEqualTo(Duration.ofMinutes(1)));
		runner.withPropertyValues("jey.billing.ledger.retry-interval=5").run(context -> assertThat(context).hasFailed());
	}

	@Test
	void 문자열로_바꿔도_키가_나오지_않는다() {
		runner.withPropertyValues("jey.billing.ledger.spreadsheets.1=1AbC",
				"jey.billing.ledger.service-account-key=c2VjcmV0LWtleS12YWx1ZQ==")
			.run(context -> assertThat(context.getBean(LedgerProperties.class).toString())
				.doesNotContain("c2VjcmV0LWtleS12YWx1ZQ=="));
	}

	@Test
	void 운영_설정_파일은_장부_연결이_꺼진_채이고_키는_환경_변수에서_받는다() throws IOException {
		var sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"));
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
		sources.forEach(environment.getPropertySources()::addLast);

		LedgerProperties production = Binder.get(environment).bind("jey.billing.ledger", LedgerProperties.class).get();

		assertThat(production.spreadsheetFor(1)).isEmpty();
		assertThat(sources.getFirst().getProperty("jey.billing.ledger.service-account-key"))
			.hasToString("${GOOGLE_LEDGER_SA_KEY_B64:}");
	}

	@Configuration
	@EnableConfigurationProperties(LedgerProperties.class)
	static class Config {

	}

}
