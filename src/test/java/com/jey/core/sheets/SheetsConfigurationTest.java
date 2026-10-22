package com.jey.core.sheets;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import com.jey.TestServiceAccountKeys;
import com.jey.core.sheets.api.Spreadsheets;

// 키가 없으면 시트 연결이 꺼진 채로 뜨고, 키가 있는데 깨졌거나 시간 제한이 너무 길면 서버가 뜨지 않는다. Docker가 필요 없다.
class SheetsConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(SheetsConfiguration.class);

	@Test
	void 키가_없으면_시트_연결이_꺼진_채로_뜬다() {
		runner.run(context -> {
			assertThat(context).hasNotFailed().hasSingleBean(Spreadsheets.class);
			assertThat(context.getBean(Spreadsheets.class).enabled()).isFalse();
		});
	}

	@Test
	void 키가_있으면_켜진다() {
		runner.withPropertyValues("jey.sheets.service-account-key=" + TestServiceAccountKeys.base64())
			.run(context -> assertThat(context.getBean(Spreadsheets.class).enabled()).isTrue());
	}

	@Test
	void 키가_있는데_깨졌으면_서버가_뜨지_않는다() {
		runner.withPropertyValues("jey.sheets.service-account-key=bm90LWEta2V5")
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void 문자열로_바꿔도_키가_나오지_않는다() {
		SheetsProperties properties = new SheetsProperties("https://sheets.googleapis.com", Duration.ofSeconds(3),
				Duration.ofSeconds(10), "c2VjcmV0LWtleS12YWx1ZQ==");

		assertThat(properties.toString()).doesNotContain("c2VjcmV0LWtleS12YWx1ZQ==");
	}

	@Test
	void 운영_설정_파일의_키는_환경_변수에서_받는다() throws IOException {
		var sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"));

		assertThat(sources.getFirst().getProperty("jey.sheets.service-account-key"))
			.hasToString("${GOOGLE_LEDGER_SA_KEY_B64:}");
	}

	@Test
	void 시간_제한은_연결_5초_응답_15초까지다() {
		runner.withPropertyValues("jey.sheets.connect-timeout=6s").run(context -> assertThat(context).hasFailed());
		runner.withPropertyValues("jey.sheets.read-timeout=16s").run(context -> assertThat(context).hasFailed());
		runner.withPropertyValues("jey.sheets.connect-timeout=5s", "jey.sheets.read-timeout=15s")
			.run(context -> assertThat(context).hasNotFailed());
	}

}
