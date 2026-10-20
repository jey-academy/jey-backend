package com.jey.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
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
import com.jey.core.shared.Money;
import com.jey.core.shared.UniqueKeys;

// 청구는 학생 한 명의 한 달 수강료. 한 학생 한 달에 열린 청구는 하나다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class InvoicePersistenceTest {

	private static final YearMonth NOVEMBER = YearMonth.of(2026, 11);

	@Autowired
	InvoiceRepository invoices;

	@Autowired
	TransactionTemplate transaction;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void 저장한_청구를_다시_읽으면_금액과_할인_순서와_대상_월이_같다() {
		long student = newStudentId();
		Invoice saved = invoices.save(invoice(student, NOVEMBER));

		transaction.executeWithoutResult(status -> {
			Invoice read = invoices.findById(saved.getId()).orElseThrow();
			assertThat(read.getTargetMonth()).isEqualTo(NOVEMBER);
			assertThat(read.getStudentId()).isEqualTo(student);
			assertThat(read.getDiscountPolicy()).isEqualTo(DiscountPolicy.SEQUENTIAL);
			assertThat(read.getTotalAmount()).isEqualTo(Money.of(252_700 + 133_000));
			assertThat(read.getLines()).extracting(InvoiceLine::getClassId).containsExactly(7L, 3L);
			InvoiceLine first = read.getLines().get(0);
			assertThat(first.getBaseFee8()).isEqualTo(Money.of(280_000));
			assertThat(first.getListAmount()).isEqualTo(Money.of(280_000));
			assertThat(first.getChargeAmount()).isEqualTo(Money.of(252_700));
			assertThat(first.getDiscounts()).extracting(InvoiceLineDiscount::getDiscountTypeCode)
				.containsExactly("DONGTAN_PASS", "SIBLING_5");
			assertThat(first.getDiscounts()).extracting(InvoiceLineDiscount::getAmount)
				.containsExactly(Money.of(14_000), Money.of(13_300));
			assertThat(first.getDiscounts().get(0).getRate()).isEqualByComparingTo("0.05");
			assertThat(first.getDiscounts().get(0).getDiscountRef()).isNull();
		});
		assertThat(jdbc.queryForObject("SELECT target_month FROM billing_invoice WHERE id = ?", String.class,
				saved.getId())).isEqualTo("2026-11");
	}

	@Test
	void 같은_학생의_같은_달_열린_청구는_하나다() {
		long student = newStudentId();
		invoices.save(invoice(student, NOVEMBER));

		assertThatThrownBy(() -> invoices.saveAndFlush(invoice(student, NOVEMBER)))
			.isInstanceOfSatisfying(DataIntegrityViolationException.class,
					e -> assertThat(UniqueKeys.isViolationOf(e, Invoice.OPEN_UNIQUE_KEY)).isTrue());
	}

	@Test
	void 다른_달이나_다른_학생이면_따로_만든다() {
		long student = newStudentId();
		invoices.save(invoice(student, NOVEMBER));

		invoices.save(invoice(student, NOVEMBER.plusMonths(1)));
		invoices.save(invoice(newStudentId(), NOVEMBER));

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM billing_invoice WHERE student_id = ?", Integer.class,
				student)).isEqualTo(2);
	}

	// 열린 청구만 open_key = 1이다. 둘이 어긋난 행은 DB가 받지 않는다(열린 청구가 유니크 키를 빠져나가지 않게).
	// MySQL은 CHECK 위반(3819)을 무결성 오류로 분류하지 않아 스프링이 분류하지 못한 예외가 된다. 제약 이름으로 본다.
	@Test
	void 열린_청구의_표시는_상태와_맞아야_한다() {
		Invoice saved = invoices.save(invoice(newStudentId(), NOVEMBER));

		assertThatThrownBy(
				() -> jdbc.update("UPDATE billing_invoice SET open_key = NULL WHERE id = ?", saved.getId()))
			.hasMessageContaining("ck_billing_invoice_open_key");
		assertThatThrownBy(() -> jdbc.update("UPDATE billing_invoice SET open_key = 2 WHERE id = ?", saved.getId()))
			.hasMessageContaining("ck_billing_invoice_open_key");
	}

	private static Invoice invoice(long studentId, YearMonth month) {
		Money fee = Money.of(280_000);
		List<DiscountRate> discounts = List.of(new DiscountRate("DONGTAN_PASS", new BigDecimal("0.05")),
				new DiscountRate("SIBLING_5", new BigDecimal("0.05")));
		InvoiceLine first = InvoiceLine.of(7, 8, fee,
				TuitionCalculator.charge(fee, 8, discounts, DiscountPolicy.SEQUENTIAL));
		InvoiceLine second = InvoiceLine.of(3, 8, Money.of(133_000),
				TuitionCalculator.charge(Money.of(133_000), 8, List.of(), DiscountPolicy.SEQUENTIAL));
		return Invoice.issue(1, studentId, month, DiscountPolicy.SEQUENTIAL, List.of(first, second));
	}

	// 학생은 다른 모듈의 자료라 외래 키가 없다. 테스트마다 겹치지 않는 ID를 쓴다.
	private static long newStudentId() {
		return ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE / 2);
	}

}
