package com.jey.billing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.time.YearMonth;
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

// 장부 설정. 지점마다 스프레드시트 하나다(구글 연결과 키는 jey.sheets — SheetsConfigurationTest). Docker가 필요 없다.
class LedgerPropertiesTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

	@Test
	void 아무것도_정하지_않으면_장부_연결이_꺼진다() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(LedgerProperties.class).spreadsheetFor(1)).isEmpty();
		});
	}

	@Test
	void 운영의_환경_변수_이름으로_지점의_스프레드시트를_정한다() {
		runner
			// 실제 환경 변수 출처와 같은 이름이어야 환경 변수 이름 규칙(_ → .)이 적용된다.
			.withInitializer(context -> context.getEnvironment().getPropertySources()
				.replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
						new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
						Map.of("JEY_BILLING_LEDGER_SPREADSHEETS_1", "1AbC-dEf_123"))))
			.run(context -> {
				assertThat(context).hasNotFailed();
				LedgerProperties properties = context.getBean(LedgerProperties.class);
				assertThat(properties.spreadsheetFor(1)).contains("1AbC-dEf_123");
				assertThat(properties.spreadsheetFor(2)).isEmpty();
			});
	}

	@Test
	void 스프레드시트를_정했는데_구글_시트_연결이_꺼져_있으면_막는다() {
		LedgerProperties configured = new LedgerProperties(Map.of(1L, "1AbC"), Duration.ofMinutes(1), null);

		assertThatThrownBy(() -> configured.requireSheetsIfConfigured(false)).isInstanceOf(IllegalStateException.class);
		configured.requireSheetsIfConfigured(true);
		new LedgerProperties(Map.of(), Duration.ofMinutes(1), null).requireSheetsIfConfigured(false);
	}

	@Test
	void 스프레드시트_ID의_모양이_틀리면_서버가_뜨지_않는다() {
		runner.withPropertyValues("jey.billing.ledger.spreadsheets.1=https://docs.google.com/x").run(context -> assertThat(context).hasFailed());
	}

	@Test
	void 다시_시도_간격은_기본_1분이고_10초보다_짧으면_서버가_뜨지_않는다() {
		runner.run(context -> assertThat(context.getBean(LedgerProperties.class).retryInterval())
			.isEqualTo(Duration.ofMinutes(1)));
		runner.withPropertyValues("jey.billing.ledger.retry-interval=5").run(context -> assertThat(context).hasFailed());
	}

	@Test
	void 탭_이름의_틀은_기본이_실제_장부_모양이다() {
		runner.run(context -> assertThat(context.getBean(LedgerProperties.class)
			.tabNameFor(YearMonth.of(2026, 11), "동탄관")).isEqualTo("11월 동탄관(26)"));
	}

	@Test
	void 탭_이름의_틀이_틀리면_서버가_뜨지_않는다() {
		runner.withPropertyValues("jey.billing.ledger.tab-name={month}월 {campus}")
			.run(context -> assertThat(context).hasFailed());
	}

	// 두 지점이 한 파일을 쓰면 탭 이름에 지점이 있어야 한다. 없으면 두 지점의 결제가 같은 탭에 섞인다.
	@Test
	void 두_지점이_같은_파일인데_탭_이름에_지점이_없으면_서버가_뜨지_않는다() {
		runner.withPropertyValues("jey.billing.ledger.spreadsheets.1=1AbC", "jey.billing.ledger.spreadsheets.2=1AbC",
				"jey.billing.ledger.tab-name={year}-{month2}")
			.run(context -> assertThat(context).hasFailed());
		runner.withPropertyValues("jey.billing.ledger.spreadsheets.1=1AbC", "jey.billing.ledger.spreadsheets.2=1AbC")
			.run(context -> assertThat(context).hasNotFailed());
	}

	@Test
	void 운영_설정_파일은_장부_연결이_꺼진_채다() throws IOException {
		var sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"));
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
		sources.forEach(environment.getPropertySources()::addLast);

		LedgerProperties production = Binder.get(environment).bindOrCreate("jey.billing.ledger", LedgerProperties.class);

		assertThat(production.spreadsheetFor(1)).isEmpty();
	}

	@Configuration
	@EnableConfigurationProperties(LedgerProperties.class)
	static class Config {

	}

}
