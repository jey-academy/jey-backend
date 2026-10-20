package com.jey.billing.application;

import static com.jey.core.member.MemberFixtures.mother;
import static com.jey.core.member.MemberFixtures.randomPhone;
import static com.jey.core.member.MemberFixtures.registration;
import static com.jey.core.member.MemberFixtures.uniqueName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.billing.api.PaymentCanceled;
import com.jey.billing.api.PaymentCompleted;
import com.jey.billing.api.PaymentMethod;
import com.jey.billing.domain.InvoiceStatus;
import com.jey.billing.domain.PaymentStatus;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.CampusScope;
import com.jey.core.auth.api.UserRole;
import com.jey.core.campus.ClassService;
import com.jey.core.member.MemberFixtures;
import com.jey.core.member.StudentService;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.Money;

// 데스크가 받은 결제를 청구에 기록하고 취소한다. 결제 완료·취소는 이벤트로 나가고(장부 기록이 구독한다) 감사 로그에 남는다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
@RecordApplicationEvents
@WithJeyUser(id = PaymentServiceTest.STAFF_ID, role = UserRole.STAFF, campusId = MemberFixtures.DONGTAN)
class PaymentServiceTest {

	static final long STAFF_ID = 900_891L;

	private static final AuthenticatedUser STAFF = new AuthenticatedUser(STAFF_ID, "staff", "직원", UserRole.STAFF,
			MemberFixtures.DONGTAN, null);

	private static final AuthenticatedUser OTHER_CAMPUS_STAFF = new AuthenticatedUser(900_892L, "staff2", "직원",
			UserRole.STAFF, MemberFixtures.NEUNGDONG, null);

	private static final YearMonth NOVEMBER = YearMonth.of(2026, 11);

	@Autowired
	PaymentService payments;

	@Autowired
	InvoiceService invoices;

	@Autowired
	StudentService students;

	@Autowired
	ClassService classes;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ApplicationEvents events;

	@Test
	void 결제하면_청구가_PAID이고_교재비는_따로_남고_완료_이벤트가_나간다() {
		InvoiceDetail invoice = newInvoice();

		PaymentView paid = payments.record(invoice.id(), PaymentMethod.CARD_POS, Money.of(280_000), Money.of(15_000),
				STAFF);

		assertThat(paid.status()).isEqualTo(PaymentStatus.COMPLETED);
		assertThat(paid.amount()).isEqualTo(Money.of(280_000));
		assertThat(paid.bookFee()).isEqualTo(Money.of(15_000));
		InvoiceDetail read = invoices.get(invoice.id(), STAFF);
		assertThat(read.status()).isEqualTo(InvoiceStatus.PAID);
		assertThat(read.payment()).isEqualTo(paid);
		assertThat(events.stream(PaymentCompleted.class)).singleElement().satisfies(event -> {
			assertThat(event.paymentId()).isEqualTo(paid.paymentId());
			assertThat(event.invoiceId()).isEqualTo(invoice.id());
			assertThat(event.studentId()).isEqualTo(invoice.studentId());
			assertThat(event.targetMonth()).isEqualTo("2026-11");
			assertThat(event.method()).isEqualTo(PaymentMethod.CARD_POS);
			assertThat(event.amount()).isEqualTo(Money.of(280_000));
			assertThat(event.bookFee()).isEqualTo(Money.of(15_000));
			assertThat(event.metadata().campusId()).isEqualTo(MemberFixtures.DONGTAN);
			assertThat(event.metadata().actorId()).isEqualTo(STAFF_ID);
			assertThat(event.subject()).hasValueSatisfying(ref -> {
				assertThat(ref.type()).isEqualTo("student");
				assertThat(ref.id()).isEqualTo(invoice.studentId());
			});
		});
		// 이벤트는 감사 로그에 남는다(리스너가 커밋 뒤에 적는다).
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM core_audit_log WHERE action = 'billing.payment-completed' AND target_id = ?",
				Long.class, invoice.studentId())).isOne());
	}

	@Test
	void 수강료가_청구_합계와_다르면_409이고_아무것도_바뀌지_않는다() {
		InvoiceDetail invoice = newInvoice();

		assertRejected(() -> payments.record(invoice.id(), PaymentMethod.CASH, Money.of(270_000), Money.ZERO, STAFF),
				"BILLING_PAYMENT_AMOUNT_MISMATCH");
		assertThat(invoices.get(invoice.id(), STAFF).status()).isEqualTo(InvoiceStatus.ISSUED);
		assertThat(paymentCount(invoice.id())).isZero();
		assertThat(events.stream(PaymentCompleted.class)).isEmpty();
	}

	@Test
	void 결제한_청구에는_다시_결제하지_못한다() {
		InvoiceDetail invoice = newInvoice();
		pay(invoice);

		assertRejected(() -> pay(invoice), "BILLING_INVOICE_NOT_PAYABLE");
		assertThat(paymentCount(invoice.id())).isOne();
	}

	@Test
	void 결제를_취소하면_청구가_다시_결제_전이고_다시_결제할_수_있다() {
		InvoiceDetail invoice = newInvoice();
		PaymentView first = pay(invoice);

		PaymentView canceled = payments.cancel(first.paymentId(), STAFF);

		assertThat(canceled.status()).isEqualTo(PaymentStatus.CANCELED);
		assertThat(canceled.canceledAt()).isNotNull();
		assertThat(invoices.get(invoice.id(), STAFF).status()).isEqualTo(InvoiceStatus.ISSUED);
		assertThat(invoices.get(invoice.id(), STAFF).payment()).isNull();
		assertThat(events.stream(PaymentCanceled.class)).singleElement()
			.satisfies(event -> assertThat(event.paymentId()).isEqualTo(first.paymentId()));
		assertRejected(() -> payments.cancel(first.paymentId(), STAFF), "BILLING_PAYMENT_ALREADY_CANCELED");

		PaymentView again = pay(invoice);
		assertThat(invoices.get(invoice.id(), STAFF).payment()).isEqualTo(again);
		assertThat(events.stream(PaymentCompleted.class)).hasSize(2);
	}

	@Test
	void 결제한_청구는_바로_취소하지_못하고_결제를_취소하면_청구를_취소할_수_있다() {
		InvoiceDetail invoice = newInvoice();
		PaymentView paid = pay(invoice);

		assertRejected(() -> invoices.cancel(invoice.id(), STAFF), "BILLING_INVOICE_NOT_CANCELABLE");
		payments.cancel(paid.paymentId(), STAFF);
		InvoiceDetail canceled = invoices.cancel(invoice.id(), STAFF);

		assertThat(canceled.status()).isEqualTo(InvoiceStatus.CANCELED);
		assertRejected(() -> pay(invoice), "BILLING_INVOICE_NOT_PAYABLE");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM core_audit_log WHERE action = 'billing.invoice-canceled' "
				+ "AND target_id = ?", Long.class, invoice.studentId())).isOne();
		// 닫힌 청구라 같은 달에 새 청구를 만들 수 있다.
		long cls = canceled.lines().get(0).classId();
		assertThat(invoices.issue(new InvoiceDraft(invoice.studentId(), NOVEMBER,
				List.of(new InvoiceDraft.LineDraft(cls, 8)), List.of()), STAFF).status())
			.isEqualTo(InvoiceStatus.ISSUED);
	}

	@Test
	void 없는_청구나_결제는_404다() {
		assertRejected(() -> payments.record(Long.MAX_VALUE, PaymentMethod.CASH, Money.of(1), Money.ZERO, STAFF),
				"BILLING_INVOICE_NOT_FOUND");
		assertRejected(() -> payments.cancel(Long.MAX_VALUE, STAFF), "BILLING_PAYMENT_NOT_FOUND");
		assertRejected(() -> invoices.cancel(Long.MAX_VALUE, STAFF), "BILLING_INVOICE_NOT_FOUND");
	}

	@Test
	void 다른_지점_직원은_결제하거나_취소하지_못한다() {
		InvoiceDetail invoice = newInvoice();

		assertRejected(() -> payments.record(invoice.id(), PaymentMethod.CASH, Money.of(280_000), Money.ZERO,
				OTHER_CAMPUS_STAFF), "AUTH_CAMPUS_NOT_ALLOWED");
		assertRejected(() -> invoices.cancel(invoice.id(), OTHER_CAMPUS_STAFF), "AUTH_CAMPUS_NOT_ALLOWED");
		PaymentView paid = pay(invoice);
		assertRejected(() -> payments.cancel(paid.paymentId(), OTHER_CAMPUS_STAFF), "AUTH_CAMPUS_NOT_ALLOWED");
		assertThat(events.stream(PaymentCanceled.class)).isEmpty();
	}

	@Test
	void 결제_목록은_결제일로_자르고_지점_범위를_따른다() {
		InvoiceDetail invoice = newInvoice();
		PaymentView paid = pay(invoice);
		LocalDate today = LocalDate.now(java.time.Clock.system(java.time.ZoneId.of("Asia/Seoul")));

		assertThat(payments.list(new CampusScope.Only(MemberFixtures.DONGTAN), today, today, 0, 100).getContent())
			.extracting(PaymentView::paymentId).contains(paid.paymentId());
		assertThat(payments.list(new CampusScope.Only(MemberFixtures.NEUNGDONG), today, today, 0, 100).getContent())
			.extracting(PaymentView::paymentId).doesNotContain(paid.paymentId());
		assertThat(payments.list(new CampusScope.Only(MemberFixtures.DONGTAN), today.minusDays(3), today.minusDays(1),
				0, 100).getContent())
			.extracting(PaymentView::paymentId).doesNotContain(paid.paymentId());
		assertRejected(() -> payments.list(new CampusScope.All(), today, today.minusDays(1), 0, 20),
				"BILLING_INVALID_PERIOD");
		assertRejected(() -> payments.list(new CampusScope.All(), today, today.plusYears(1).plusDays(1), 0, 20),
				"BILLING_PERIOD_TOO_LONG");
	}

	// 결제 목록은 일일 정산에 쓰인다. 결제일은 한국 날짜다: 한국 11/1 23:59:59.999999는 11/1, 한국 11/2 00:00은 11/2.
	@Test
	void 결제일은_한국_날짜로_자른다() {
		InvoiceDetail late = newInvoice();
		InvoiceDetail midnight = newInvoice();
		InvoiceDetail early = newInvoice();
		long lateId = insertPayment(late, "2026-11-01 23:59:59.999999");
		long midnightId = insertPayment(midnight, "2026-11-02 00:00:00.000000");
		long earlyId = insertPayment(early, "2026-11-01 00:00:00.000000");
		CampusScope dongtan = new CampusScope.Only(MemberFixtures.DONGTAN);

		assertThat(payments.list(dongtan, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1), 0, 100).getContent())
			.extracting(PaymentView::paymentId).contains(lateId, earlyId).doesNotContain(midnightId);
		assertThat(payments.list(dongtan, LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 2), 0, 100).getContent())
			.extracting(PaymentView::paymentId).contains(midnightId).doesNotContain(lateId, earlyId);
	}

	// 청구를 잠그고 상태를 보므로 보통은 INVOICE_NOT_PAYABLE이 먼저다. 유니크 키가 막은 마지막 방어선도 409로 바뀌는지 본다
	// (청구는 결제 전인데 유효한 결제가 이미 있는, 어긋난 자료를 직접 넣어 만든다).
	@Test
	void 유효한_결제가_이미_있으면_마지막_방어선이_409로_답하고_이벤트가_나가지_않는다() {
		InvoiceDetail invoice = newInvoice();
		insertPayment(invoice, "2026-11-01 01:00:00.000000");
		jdbc.update("UPDATE billing_invoice SET status = 'ISSUED' WHERE id = ?", invoice.id());

		assertRejected(() -> pay(invoice), "BILLING_PAYMENT_ALREADY_EXISTS");
		assertThat(jdbc.queryForObject("SELECT status FROM billing_invoice WHERE id = ?", String.class, invoice.id()))
			.isEqualTo("ISSUED");
		assertThat(events.stream(PaymentCompleted.class)).isEmpty();
	}

	@Test
	void 교재비가_규칙_밖이면_400이고_결제하지_않는다() {
		InvoiceDetail invoice = newInvoice();

		assertRejected(() -> payments.record(invoice.id(), PaymentMethod.CASH, invoice.totalAmount(),
				Money.of(10_000_001), STAFF), "BILLING_INVALID_BOOK_FEE");
		assertThat(paymentCount(invoice.id())).isZero();
	}

	// DB의 시각 칸은 한국 시각으로 저장한다(ADR-0017의 JPA 설정). 결제한 청구와 유효한 결제를 직접 넣는다.
	private long insertPayment(InvoiceDetail invoice, String paidAtSeoul) {
		jdbc.update("UPDATE billing_invoice SET status = 'PAID' WHERE id = ?", invoice.id());
		jdbc.update("INSERT INTO billing_payment (invoice_id, campus_id, student_id, method, amount, book_fee, status, "
				+ "open_key, paid_at, created_at, updated_at) VALUES (?, ?, ?, 'CASH', ?, 0, 'COMPLETED', 1, ?, "
				+ "NOW(6), NOW(6))", invoice.id(), invoice.campusId(), invoice.studentId(),
				invoice.totalAmount().amount(), paidAtSeoul);
		return jdbc.queryForObject("SELECT id FROM billing_payment WHERE invoice_id = ?", Long.class, invoice.id());
	}

	private PaymentView pay(InvoiceDetail invoice) {
		return payments.record(invoice.id(), PaymentMethod.CASH, invoice.totalAmount(), Money.ZERO, STAFF);
	}

	private InvoiceDetail newInvoice() {
		long student = students.register(registration(uniqueName(), mother(randomPhone()))).id();
		long cls = classes.create(MemberFixtures.DONGTAN, "반-" + UUID.randomUUID().toString().substring(0, 8),
				Money.of(280_000), Money.ZERO).getId();
		return invoices.issue(new InvoiceDraft(student, NOVEMBER, List.of(new InvoiceDraft.LineDraft(cls, 8)),
				List.of()), STAFF);
	}

	private long paymentCount(long invoiceId) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM billing_payment WHERE invoice_id = ?", Long.class, invoiceId);
	}

	private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
		assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
				e -> assertThat(e.getErrorCode().code()).isEqualTo(code));
	}

}
