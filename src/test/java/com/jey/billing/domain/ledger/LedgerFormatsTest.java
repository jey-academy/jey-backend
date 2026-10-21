package com.jey.billing.domain.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.jey.core.shared.Money;
import com.jey.core.shared.PhoneNumber;

class LedgerFormatsTest {

	@Test
	void 휴대전화_11자리는_괄호와_하이픈으로_쓴다() {
		assertThat(LedgerFormats.phone(PhoneNumber.of("01012345678"))).isEqualTo("(010) 1234-5678");
	}

	@Test
	void 휴대전화_10자리는_가운데가_세_자리다() {
		assertThat(LedgerFormats.phone(PhoneNumber.of("0111234567"))).isEqualTo("(011) 123-4567");
	}

	@Test
	void 금액은_쉼표와_원으로_쓴다() {
		assertThat(LedgerFormats.won(Money.of(29_000))).isEqualTo("29,000원");
		assertThat(LedgerFormats.won(Money.of(1_234_567))).isEqualTo("1,234,567원");
	}

	@Test
	void 날짜는_월과_일만_앞의_0_없이_쓴다() {
		assertThat(LedgerFormats.date(LocalDate.of(2026, 10, 31))).isEqualTo("10/31");
		assertThat(LedgerFormats.date(LocalDate.of(2026, 11, 2))).isEqualTo("11/2");
	}

	@Test
	void 앱의_표시는_결제_ID로_만든다() {
		assertThat(LedgerFormats.marker(123)).isEqualTo("앱:결제-123");
	}

	@Test
	void 취소_표시는_앱의_표시_뒤에_취소한_날을_붙인다() {
		assertThat(LedgerFormats.canceledNote(123, LocalDate.of(2026, 10, 31))).isEqualTo("앱:결제-123 취소됨(10/31)");
	}

	@Test
	void 비고가_그_결제의_표시인지는_다른_번호의_앞부분과_헷갈리지_않는다() {
		assertThat(LedgerFormats.isMarkerOf("앱:결제-123", 123)).isTrue();
		assertThat(LedgerFormats.isMarkerOf("  앱:결제-123 취소됨(10/31) ", 123)).isTrue();
		assertThat(LedgerFormats.isMarkerOf("앱:결제-1234", 123)).isFalse();
		assertThat(LedgerFormats.isMarkerOf("앱:결제-12", 123)).isFalse();
		assertThat(LedgerFormats.isMarkerOf("김김김 동생", 123)).isFalse();
		assertThat(LedgerFormats.isMarkerOf(null, 123)).isFalse();
	}

	@Test
	void 열_번호는_A1_표기의_글자로_바꾼다() {
		assertThat(LedgerFormats.column(0)).isEqualTo("A");
		assertThat(LedgerFormats.column(16)).isEqualTo("Q");
		assertThat(LedgerFormats.column(25)).isEqualTo("Z");
		assertThat(LedgerFormats.column(26)).isEqualTo("AA");
		assertThat(LedgerFormats.column(27)).isEqualTo("AB");
	}

}
