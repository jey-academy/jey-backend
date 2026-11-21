package com.jey.dongtanpass.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.jey.core.sheets.api.Spreadsheets;

// 연계 학원 장부 설정을 서버가 뜰 때 확인한다. Docker가 필요 없다.
@ExtendWith(OutputCaptureExtension.class)
class PartnerLedgerConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(PartnerLedgerConfiguration.class)
		.withBean(Spreadsheets.class, PartnerLedgerConfigurationTest::enabledSheets);

	// 시트를 쓸 학원을 잘못 넣으면 그 학원의 기록이 미반영 목록에 드러나지 않는다. 뜰 때의 로그로 알아챈다.
	@Test
	void 시트를_쓰지_않는_학원의_목록을_뜰_때_작은_번호부터_로그에_남긴다(CapturedOutput output) {
		runner.withPropertyValues("jey.dongtanpass.partner-ledger.without-sheet=7,3").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(output.getOut()).contains("시트를 쓰지 않는 연계 학원(장부 반영을 건너뛴다): [3, 7]");
		});
	}

	@Test
	void 시트를_쓰지_않는_학원이_없으면_그_로그를_남기지_않는다(CapturedOutput output) {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(output.getOut()).doesNotContain("시트를 쓰지 않는 연계 학원");
		});
	}

	private static Spreadsheets enabledSheets() {
		Spreadsheets sheets = mock(Spreadsheets.class);
		when(sheets.enabled()).thenReturn(true);
		return sheets;
	}

}
