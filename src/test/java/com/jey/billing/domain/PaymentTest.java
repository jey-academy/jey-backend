package com.jey.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.jey.billing.api.PaymentMethod;
import com.jey.core.shared.Money;

// 데스크가 받은 결제의 기록. 수강료는 청구 합계와 같아야 하고(나눠 내기 없음), 교재비는 따로 적는다.
class PaymentTest {

	private static final Instant AT = Instant.parse("2026-10-21T01:00:00Z");

	@Test
	void 결제를_기록하면_청구가_PAID다() {
		Invoice invoice = InvoiceStateTest.issued();

		Payment payment = Payment.record(invoice, PaymentMethod.CARD_POS, Money.of(280_000), Money.of(15_000), AT);

		assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
		assertThat(payment.getAmount()).isEqualTo(Money.of(280_000));
		assertThat(payment.getBookFee()).isEqualTo(Money.of(15_000));
		assertThat(payment.getMethod()).isEqualTo(PaymentMethod.CARD_POS);
		assertThat(payment.getPaidAt()).isEqualTo(AT);
		assertThat(payment.getCanceledAt()).isNull();
	}

	@Test
	void 수강료가_청구_합계와_다르면_거부하고_청구는_그대로다() {
		Invoice invoice = InvoiceStateTest.issued();

		assertThatThrownBy(() -> Payment.record(invoice, PaymentMethod.CASH, Money.of(279_999), Money.ZERO, AT))
			.isInstanceOf(PaymentAmountMismatchException.class);
		assertThatThrownBy(() -> Payment.record(invoice, PaymentMethod.CASH, Money.of(280_001), Money.ZERO, AT))
			.isInstanceOf(PaymentAmountMismatchException.class);
		assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.ISSUED);
	}

	@Test
	void 교재비는_0원_이상_1원_단위이고_1000만원까지다() {
		assertThat(Payment.record(InvoiceStateTest.issued(), PaymentMethod.CASH, Money.of(280_000),
				Money.of(10_000_000), AT).getBookFee()).isEqualTo(Money.of(10_000_000));
		assertThatThrownBy(() -> Payment.record(InvoiceStateTest.issued(), PaymentMethod.CASH, Money.of(280_000),
				Money.of(10_000_001), AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Payment.record(InvoiceStateTest.issued(), PaymentMethod.CASH, Money.of(280_000),
				Money.of(new BigDecimal("0.5")), AT))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 이미_결제한_청구나_취소한_청구에는_결제하지_못한다() {
		Invoice paid = InvoiceStateTest.issued();
		Payment.record(paid, PaymentMethod.CASH, Money.of(280_000), Money.ZERO, AT);
		Invoice canceled = InvoiceStateTest.issued();
		canceled.cancel();

		assertThatThrownBy(() -> Payment.record(paid, PaymentMethod.CASH, Money.of(280_000), Money.ZERO, AT))
			.isInstanceOf(InvoiceStateException.class);
		assertThatThrownBy(() -> Payment.record(canceled, PaymentMethod.CASH, Money.of(280_000), Money.ZERO, AT))
			.isInstanceOf(InvoiceStateException.class);
	}

	@Test
	void 결제를_취소하면_청구가_다시_결제_전이다() {
		Invoice invoice = InvoiceStateTest.issued();
		Payment payment = Payment.record(invoice, PaymentMethod.CARD_POS, Money.of(280_000), Money.ZERO, AT);

		payment.cancel(invoice, AT.plusSeconds(60));

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
		assertThat(payment.getCanceledAt()).isEqualTo(AT.plusSeconds(60));
		assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.ISSUED);
	}

	@Test
	void 취소한_결제는_다시_취소하지_못한다() {
		Invoice invoice = InvoiceStateTest.issued();
		Payment payment = Payment.record(invoice, PaymentMethod.CASH, Money.of(280_000), Money.ZERO, AT);
		payment.cancel(invoice, AT);

		assertThatThrownBy(() -> payment.cancel(invoice, AT)).isInstanceOf(PaymentAlreadyCanceledException.class);
		assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.ISSUED);
	}

}
