package com.jey.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.jey.core.shared.Money;

class InvoiceTest {

	private static final YearMonth NOVEMBER = YearMonth.of(2026, 11);

	@Test
	void 합계는_라인_청구액을_더한_것이다() {
		// 반 나눔: 280,000원 반 9회(형제 5%) + 133,000원 반 8회
		InvoiceLine first = line(1, Money.of(280_000), 9, new DiscountRate("SIBLING_5", new BigDecimal("0.05")));
		InvoiceLine second = line(2, Money.of(133_000), 8);

		Invoice invoice = Invoice.issue(10, 20, NOVEMBER, DiscountPolicy.ADDITIVE, List.of(first, second));

		assertThat(invoice.getTotalAmount()).isEqualTo(Money.of(299_250 + 133_000));
		assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.ISSUED);
		assertThat(invoice.getLines()).extracting(InvoiceLine::getClassId).containsExactly(1L, 2L);
	}

	@Test
	void 라인마다_끊고_합계에서는_다시_끊지_않는다() {
		// 285,000원 · 5% · 9회 = 304,593.75 → 304,593. 두 반이면 609,186(합계를 먼저 더해 끊으면 609,187).
		DiscountRate sibling = new DiscountRate("SIBLING_5", new BigDecimal("0.05"));
		Invoice invoice = Invoice.issue(10, 20, NOVEMBER, DiscountPolicy.ADDITIVE,
				List.of(line(1, Money.of(285_000), 9, sibling), line(2, Money.of(285_000), 9, sibling)));

		assertThat(invoice.getTotalAmount()).isEqualTo(Money.of(609_186));
	}

	@Test
	void 라인은_1개부터_4개까지다() {
		assertThatThrownBy(() -> Invoice.issue(10, 20, NOVEMBER, DiscountPolicy.ADDITIVE, List.of()))
			.isInstanceOf(IllegalArgumentException.class);
		List<InvoiceLine> five = List.of(line(1, Money.of(100_000), 8), line(2, Money.of(100_000), 8),
				line(3, Money.of(100_000), 8), line(4, Money.of(100_000), 8), line(5, Money.of(100_000), 8));
		assertThatThrownBy(() -> Invoice.issue(10, 20, NOVEMBER, DiscountPolicy.ADDITIVE, five))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(Invoice.issue(10, 20, NOVEMBER, DiscountPolicy.ADDITIVE, five.subList(0, 4)).getLines())
			.hasSize(4);
	}

	@Test
	void 같은_반을_두_라인에_넣지_않는다() {
		assertThatThrownBy(() -> Invoice.issue(10, 20, NOVEMBER, DiscountPolicy.ADDITIVE,
				List.of(line(1, Money.of(280_000), 4), line(1, Money.of(280_000), 5))))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 라인은_계산_결과를_그대로_담는다() {
		InvoiceLine line = line(1, Money.of(280_000), 8, new DiscountRate("DONGTAN_PASS", new BigDecimal("0.05")),
				new DiscountRate("SIBLING_5", new BigDecimal("0.05")));

		assertThat(line.getListAmount()).isEqualTo(Money.of(280_000));
		assertThat(line.getChargeAmount()).isEqualTo(Money.of(252_000));
		assertThat(line.getBaseFee8()).isEqualTo(Money.of(280_000));
		assertThat(line.getDiscounts()).extracting(InvoiceLineDiscount::getDiscountTypeCode)
			.containsExactly("DONGTAN_PASS", "SIBLING_5");
	}

	private static InvoiceLine line(long classId, Money baseFee8, int lessons, DiscountRate... discounts) {
		return InvoiceLine.of(classId, lessons, baseFee8,
				TuitionCalculator.charge(baseFee8, lessons, List.of(discounts), DiscountPolicy.ADDITIVE));
	}

}
