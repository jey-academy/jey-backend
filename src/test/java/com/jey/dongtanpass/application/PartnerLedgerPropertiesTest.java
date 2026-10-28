package com.jey.dongtanpass.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

// 연계 학원 장부 설정. 연계 학원마다 스프레드시트 하나다. Docker가 필요 없다.
class PartnerLedgerPropertiesTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

	@Test
	void 아무것도_정하지_않으면_연결이_꺼지고_탭은_JEY_패스_다시_시도는_1분이다() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			PartnerLedgerProperties properties = context.getBean(PartnerLedgerProperties.class);
			assertThat(properties.spreadsheetFor(3)).isEmpty();
			assertThat(properties.tabName()).isEqualTo("제이G패스");
			assertThat(properties.retryInterval()).isEqualTo(Duration.ofMinutes(1));
		});
	}

	// 운영은 Parameter Store의 값을 환경 변수로 넣는다. partner-ledger는 환경 변수 이름에서 하이픈 없이 PARTNERLEDGER다.
	@Test
	void 운영의_환경_변수_이름으로_연계_학원의_스프레드시트를_정한다() {
		runner
			.withInitializer(context -> context.getEnvironment().getPropertySources()
				.replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
						new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
								Map.of("JEY_DONGTANPASS_PARTNERLEDGER_SPREADSHEETS_3", "1AbC-dEf_123"))))
			.run(context -> {
				assertThat(context).hasNotFailed();
				PartnerLedgerProperties properties = context.getBean(PartnerLedgerProperties.class);
				assertThat(properties.spreadsheetFor(3)).contains("1AbC-dEf_123");
				assertThat(properties.spreadsheetFor(4)).isEmpty();
			});
	}

	@Test
	void 스프레드시트_ID의_모양이_틀리면_서버가_뜨지_않는다() {
		runner.withPropertyValues("jey.dongtanpass.partner-ledger.spreadsheets.3=https://docs.google.com/x")
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void 다시_시도_간격이_10초보다_짧으면_서버가_뜨지_않는다() {
		runner.withPropertyValues("jey.dongtanpass.partner-ledger.retry-interval=5")
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void 탭_이름이_비었거나_100자를_넘으면_서버가_뜨지_않는다() {
		runner.withPropertyValues("jey.dongtanpass.partner-ledger.tab-name= ").run(context -> assertThat(context).hasFailed());
		runner.withPropertyValues("jey.dongtanpass.partner-ledger.tab-name=" + "가".repeat(101))
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void 장부를_정했는데_구글_시트_연결이_꺼져_있으면_막는다() {
		PartnerLedgerProperties configured = new PartnerLedgerProperties(Map.of(3L, "1AbC"), null, Duration.ofMinutes(1));

		assertThatThrownBy(() -> configured.requireSheetsIfConfigured(false)).isInstanceOf(IllegalStateException.class);
		configured.requireSheetsIfConfigured(true);
		new PartnerLedgerProperties(Map.of(), null, Duration.ofMinutes(1)).requireSheetsIfConfigured(false);
	}

	@Configuration
	@EnableConfigurationProperties(PartnerLedgerProperties.class)
	static class Config {

	}

}
