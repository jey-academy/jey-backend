package com.jey.dongtanpass.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import com.jey.core.shared.Money;
import com.jey.dongtanpass.domain.PassState;
import com.jey.dongtanpass.domain.PaymentMethod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerCsvTest {

	private static final byte[] BOM = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };

	// 표 계산 프로그램은 =, +, -, @, 탭, CR로 시작하는 칸을 수식으로 읽는다(CSV 인젝션). 앞에 '를 붙여 글자로 읽게 한다.
	@ParameterizedTest(name = "{0} → {1}")
	@CsvSource(delimiter = '|', quoteCharacter = '~', value = {
			"김하윤|\"김하윤\"",
			"=HYPERLINK(\"http://x\")|\"'=HYPERLINK(\"\"http://x\"\")\"",
			"+82|\"'+82\"",
			"-1|\"'-1\"",
			"@SUM(A1)|\"'@SUM(A1)\"",
			"a,b|\"a,b\"",
			"따옴표\"하나|\"따옴표\"\"하나\"",
			"가운데=는|\"가운데=는\"" })
	void 칸은_따옴표로_감싸고_수식으로_읽힐_첫_글자_앞에_따옴표를_붙인다(String value, String expected) {
		assertThat(LedgerCsv.cell(value)).isEqualTo(expected);
	}

	@Test
	void 탭과_CR과_줄바꿈도_다룬다() {
		assertThat(LedgerCsv.cell("\tx")).isEqualTo("\"'\tx\"");
		assertThat(LedgerCsv.cell("\rx")).isEqualTo("\"'\rx\"");
		// 가운데 줄바꿈은 따옴표 안에 그대로 둔다(한 칸으로 읽힌다)
		assertThat(LedgerCsv.cell("줄\n바꿈")).isEqualTo("\"줄\n바꿈\"");
	}

	@Test
	void 빈_값은_빈_칸이다() {
		assertThat(LedgerCsv.cell("")).isEqualTo("\"\"");
		assertThat(LedgerCsv.cell(null)).isEqualTo("\"\"");
	}

	@Test
	void 파일은_UTF8_BOM과_머리줄로_시작하고_줄은_CRLF다() {
		byte[] file = LedgerCsv.write(List.of(row("김하윤", PassState.ISSUED, PaymentMethod.CARD)));

		assertThat(Arrays.copyOf(file, 3)).isEqualTo(BOM);
		String text = new String(file, 3, file.length - 3, StandardCharsets.UTF_8);
		assertThat(text).isEqualTo("""
				"패스 번호","학생 이름","발급일","만료일","상태","결제일","결제 금액","결제 수단"\r
				"DT-ABC234","김하윤","2026-10-15","2026-11-15","발급됨","2026-10-14","150000","카드"\r
				""");
	}

	@Test
	void 줄이_없어도_머리줄은_있다() {
		byte[] file = LedgerCsv.write(List.of());

		assertThat(new String(file, 3, file.length - 3, StandardCharsets.UTF_8)).startsWith("\"패스 번호\"").hasLineCount(1);
	}

	@Test
	void 상태와_수단은_한국어로_쓴다() {
		String text = new String(LedgerCsv.write(List.of(row("김하윤", PassState.EXPIRED, PaymentMethod.TRANSFER))),
				StandardCharsets.UTF_8);

		assertThat(text).contains("\"만료\"").contains("\"계좌이체\"");
	}

	@Test
	void 수식으로_시작하는_이름도_글자로_쓴다() {
		String text = new String(LedgerCsv.write(List.of(row("=1+1", PassState.ISSUED, PaymentMethod.CARD))),
				StandardCharsets.UTF_8);

		assertThat(text).contains("\"'=1+1\"");
	}

	private static LedgerRow row(String name, PassState state, PaymentMethod method) {
		return new LedgerRow(1L, "DT-ABC234", name, Instant.parse("2026-10-15T01:00:00Z"), LocalDate.of(2026, 10, 15),
				LocalDate.of(2026, 11, 15), state, LocalDate.of(2026, 10, 14), Money.of(150000), method);
	}

}
