package com.jey.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.jey.TestcontainersConfiguration;
import com.jey.billing.api.PaymentMethod;
import com.jey.core.shared.Money;
import com.jey.core.shared.UniqueKeys;

// 결제 기록. 청구 하나에 유효한 결제(COMPLETED)는 하나다. 취소한 결제는 기록으로 남고 다시 결제할 수 있다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class PaymentPersistenceTest {

	private static final Instant AT = Instant.parse("2026-10-21T01:02:03.123456Z");

	@Autowired
	InvoiceRepository invoices;

	@Autowired
	PaymentRepository payments;

	@Autowired
	TransactionTemplate transaction;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void 저장한_결제를_다시_읽으면_모든_칸이_같고_청구는_PAID다() {
		long invoiceId = newInvoice();

		long paymentId = transaction.execute(status -> {
			Invoice invoice = invoices.findById(invoiceId).orElseThrow();
			return payments.save(Payment.record(invoice, PaymentMethod.CARD_POS, Money.of(280_000), Money.of(15_000), AT))
				.getId();
		});

		Payment read = payments.findById(paymentId).orElseThrow();
		assertThat(read.getInvoiceId()).isEqualTo(invoiceId);
		assertThat(read.getCampusId()).isEqualTo(1);
		assertThat(read.getMethod()).isEqualTo(PaymentMethod.CARD_POS);
		assertThat(read.getAmount()).isEqualTo(Money.of(280_000));
		assertThat(read.getBookFee()).isEqualTo(Money.of(15_000));
		assertThat(read.getPaidAt()).isEqualTo(AT);
		assertThat(invoices.findById(invoiceId).orElseThrow().getStatus()).isEqualTo(InvoiceStatus.PAID);
		assertThat(payments.findByInvoiceIdAndStatus(invoiceId, PaymentStatus.COMPLETED)).isPresent();
	}

	// 서비스가 청구를 잠그고 상태를 보지만, 유효한 결제가 둘 생기지 않게 하는 마지막 방어선은 유니크 키다.
	@Test
	void 같은_청구의_유효한_결제는_하나다() {
		long invoiceId = newInvoice();
		pay(invoiceId);

		assertThatThrownBy(() -> jdbc.update("INSERT INTO billing_payment (invoice_id, campus_id, student_id, method, "
				+ "amount, book_fee, status, open_key, paid_at, created_at, updated_at) "
				+ "VALUES (?, 1, 2, 'CASH', 280000, 0, 'COMPLETED', 1, NOW(6), NOW(6), NOW(6))", invoiceId))
			// 엔티티를 거치지 않고 넣었으므로(Hibernate의 예외가 아니다) 키 이름은 문구로 본다.
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining(Payment.OPEN_UNIQUE_KEY);
	}

	@Test
	void 결제를_취소하면_다시_결제할_수_있고_취소한_결제는_남는다() {
		long invoiceId = newInvoice();
		long first = pay(invoiceId);
		transaction.executeWithoutResult(status -> {
			Payment payment = payments.findById(first).orElseThrow();
			payment.cancel(invoices.findById(invoiceId).orElseThrow(), AT.plusSeconds(60));
		});

		pay(invoiceId);

		assertThat(jdbc.queryForList("SELECT status FROM billing_payment WHERE invoice_id = ? ORDER BY id",
				String.class, invoiceId)).containsExactly("CANCELED", "COMPLETED");
	}

	@Test
	void 결제의_상태와_표시와_취소_시각은_서로_맞아야_한다() {
		long invoiceId = newInvoice();
		long paymentId = pay(invoiceId);

		assertThatThrownBy(() -> jdbc.update("UPDATE billing_payment SET open_key = NULL WHERE id = ?", paymentId))
			.hasMessageContaining("ck_billing_payment_open_key");
		assertThatThrownBy(
				() -> jdbc.update("UPDATE billing_payment SET canceled_at = NOW(6) WHERE id = ?", paymentId))
			.hasMessageContaining("ck_billing_payment_canceled_at");
	}

	// 결제한 청구도 열린 청구다. 결제한 달에 청구를 하나 더 만들 수 없다. 취소한 청구는 닫혀 새 청구를 막지 않는다.
	@Test
	void 결제한_청구도_같은_달의_새_청구를_막고_취소한_청구는_막지_않는다() {
		long student = newStudentId();
		long paidId = invoices.save(invoice(student)).getId();
		pay(paidId);

		assertThatThrownBy(() -> invoices.saveAndFlush(invoice(student)))
			.isInstanceOfSatisfying(DataIntegrityViolationException.class,
					e -> assertThat(UniqueKeys.isViolationOf(e, Invoice.OPEN_UNIQUE_KEY)).isTrue());

		long other = newStudentId();
		long canceledId = invoices.save(invoice(other)).getId();
		transaction.executeWithoutResult(status -> invoices.findById(canceledId).orElseThrow().cancel());
		invoices.save(invoice(other));
		assertThat(jdbc.queryForList("SELECT status FROM billing_invoice WHERE student_id = ? ORDER BY id",
				String.class, other)).containsExactly("CANCELED", "ISSUED");
	}

	@Test
	void 청구의_새_CHECK는_PAID를_열린_청구로_본다() {
		long invoiceId = newInvoice();

		jdbc.update("UPDATE billing_invoice SET status = 'PAID' WHERE id = ?", invoiceId);
		assertThatThrownBy(() -> jdbc.update("UPDATE billing_invoice SET open_key = NULL WHERE id = ?", invoiceId))
			.hasMessageContaining("ck_billing_invoice_open_key");
		assertThatThrownBy(() -> jdbc.update("UPDATE billing_invoice SET status = 'CANCELED' WHERE id = ?", invoiceId))
			.hasMessageContaining("ck_billing_invoice_open_key");
	}

	private long pay(long invoiceId) {
		return transaction.execute(status -> payments
			.save(Payment.record(invoices.findById(invoiceId).orElseThrow(), PaymentMethod.CASH, Money.of(280_000),
					Money.ZERO, AT))
			.getId());
	}

	private long newInvoice() {
		return invoices.save(invoice(newStudentId())).getId();
	}

	private static Invoice invoice(long studentId) {
		Money fee = Money.of(280_000);
		InvoiceLine line = InvoiceLine.of(1, 8, fee, TuitionCalculator.charge(fee, 8, List.of(), DiscountPolicy.ADDITIVE));
		return Invoice.issue(1, studentId, YearMonth.of(2026, 11), DiscountPolicy.ADDITIVE, List.of(line));
	}

	private static long newStudentId() {
		return ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE / 2);
	}

}
