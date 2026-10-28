package com.jey.billing.domain.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.jey.core.shared.Money;
import com.jey.core.shared.PhoneNumber;

class LedgerRowTest {

	@Test
	void 시안의_예와_같은_값을_만든다() {
		LedgerRow row = LedgerRow.of(sample("동탄고1", List.of("제이G패스"), Money.of(29_000)));

		assertThat(row.values()).containsOnly(
				entry(LedgerColumn.SCHOOL, "동탄고1"),
				entry(LedgerColumn.NAME, "김하윤(EJ5)"),
				entry(LedgerColumn.PHONE, "(010) 1234-5678"),
				entry(LedgerColumn.CHARGE, 299_250L),
				entry(LedgerColumn.CONTENT, "11월 9회분 수강료 (제이G패스)"),
				entry(LedgerColumn.MONTHLY_BASE, 266_000L),
				entry(LedgerColumn.PAID_DATE, "10/31"),
				entry(LedgerColumn.TUITION, 299_250L),
				entry(LedgerColumn.BOOK_FEE, 29_000L),
				entry(LedgerColumn.THIS_MONTH, "11월 9회분 수강료 (제이G패스) + 교재비 (29,000원)"),
				entry(LedgerColumn.NOTE, "앱:결제-123"));
	}

	@Test
	void 교재비가_0원이면_교재비_칸을_쓰지_않고_납부_내용에도_붙이지_않는다() {
		LedgerRow row = LedgerRow.of(sample("동탄고1", List.of("제이G패스"), Money.ZERO));

		assertThat(row.values()).doesNotContainKey(LedgerColumn.BOOK_FEE);
		assertThat(row.values()).containsEntry(LedgerColumn.THIS_MONTH, "11월 9회분 수강료 (제이G패스)");
	}

	@Test
	void 할인이_없으면_괄호를_붙이지_않는다() {
		LedgerRow row = LedgerRow.of(sample("동탄고1", List.of(), Money.ZERO));

		assertThat(row.values()).containsEntry(LedgerColumn.CONTENT, "11월 9회분 수강료");
	}

	@Test
	void 할인이_여럿이면_이름을_더하기로_잇는다() {
		LedgerRow row = LedgerRow.of(sample("동탄고1", List.of("제이G패스", "형제 할인 5%"), Money.ZERO));

		assertThat(row.values()).containsEntry(LedgerColumn.CONTENT, "11월 9회분 수강료 (제이G패스 + 형제 할인 5%)");
	}

	@Test
	void 학교_표시가_없으면_학교_칸을_쓰지_않는다() {
		LedgerRow row = LedgerRow.of(sample(null, List.of(), Money.ZERO));

		assertThat(row.values()).doesNotContainKey(LedgerColumn.SCHOOL);
	}

	@Test
	void 장부_항목의_문자열에는_이름과_전화번호가_나오지_않는다() {
		LedgerEntry entry = sample("동탄고1", List.of(), Money.ZERO);

		assertThat(entry.toString()).doesNotContain("김하윤").doesNotContain("1234").contains("123");
	}

	private static LedgerEntry sample(String school, List<String> discounts, Money bookFee) {
		return new LedgerEntry(123L, school, "김하윤", "EJ5", PhoneNumber.of("01012345678"), YearMonth.of(2026, 11), 9,
				discounts, Money.of(299_250), Money.of(266_000), LocalDate.of(2026, 10, 31), Money.of(299_250), bookFee);
	}

}
