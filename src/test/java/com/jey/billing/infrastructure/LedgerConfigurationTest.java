package com.jey.billing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.jey.core.sheets.api.Spreadsheets;

// 지점의 장부를 정했는데 구글 시트 연결(서비스 계정 키)이 꺼져 있으면 서버가 뜨지 않는다. Docker가 필요 없다.
class LedgerConfigurationTest {

	@Test
	void 장부를_정했는데_구글_시트_연결이_꺼져_있으면_서버가_뜨지_않는다() {
		runner(false).withPropertyValues("jey.billing.ledger.spreadsheets.1=1AbC")
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void 장부를_정하지_않았으면_연결이_꺼져_있어도_뜬다() {
		runner(false).run(context -> assertThat(context).hasNotFailed());
	}

	@Test
	void 장부를_정했고_연결이_켜져_있으면_뜬다() {
		runner(true).withPropertyValues("jey.billing.ledger.spreadsheets.1=1AbC")
			.run(context -> assertThat(context).hasNotFailed());
	}

	private static ApplicationContextRunner runner(boolean sheetsEnabled) {
		Spreadsheets sheets = mock(Spreadsheets.class);
		given(sheets.enabled()).willReturn(sheetsEnabled);
		return new ApplicationContextRunner().withBean(Spreadsheets.class, () -> sheets)
			.withUserConfiguration(LedgerConfiguration.class);
	}

}
