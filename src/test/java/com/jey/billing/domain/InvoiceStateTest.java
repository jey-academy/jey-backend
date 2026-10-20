package com.jey.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.jey.core.shared.Money;

// 청구: ISSUED →(결제) PAID →(결제 취소) ISSUED, ISSUED →(취소) CANCELED. 그 밖의 전이는 거부한다.
class InvoiceStateTest {

	@Test
	void 결제하면_PAID이고_결제를_취소하면_다시_ISSUED다() {
		Invoice invoice = issued();

		invoice.markPaid();
		assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
		invoice.reopen();
		assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.ISSUED);
	}

	@Test
	void 결제_전_청구는_취소할_수_있다() {
		Invoice invoice = issued();

		invoice.cancel();

		assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.CANCELED);
	}

	@Test
	void 결제한_청구는_바로_취소하지_못하고_두_번_결제하지_못한다() {
		Invoice invoice = issued();
		invoice.markPaid();

		assertRejected(invoice::cancel, InvoiceStatus.PAID);
		assertRejected(invoice::markPaid, InvoiceStatus.PAID);
		assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
	}

	@Test
	void 결제_전_청구는_결제를_되돌릴_것이_없다() {
		assertRejected(issued()::reopen, InvoiceStatus.ISSUED);
	}

	@Test
	void 취소한_청구로는_아무것도_하지_못한다() {
		Invoice invoice = issued();
		invoice.cancel();

		assertRejected(invoice::markPaid, InvoiceStatus.CANCELED);
		assertRejected(invoice::reopen, InvoiceStatus.CANCELED);
		assertRejected(invoice::cancel, InvoiceStatus.CANCELED);
	}

	private static void assertRejected(Runnable transition, InvoiceStatus current) {
		assertThatThrownBy(transition::run).isInstanceOfSatisfying(InvoiceStateException.class,
				e -> assertThat(e.current()).isEqualTo(current));
	}

	static Invoice issued() {
		Money fee = Money.of(280_000);
		InvoiceLine line = InvoiceLine.of(1, 8, fee, TuitionCalculator.charge(fee, 8, List.of(), DiscountPolicy.ADDITIVE));
		return Invoice.issue(1, 2, YearMonth.of(2026, 11), DiscountPolicy.ADDITIVE, List.of(line));
	}

}
