package com.jey.billing.application;

import static com.jey.core.member.MemberFixtures.mother;
import static com.jey.core.member.MemberFixtures.randomPhone;
import static com.jey.core.member.MemberFixtures.registration;
import static com.jey.core.member.MemberFixtures.uniqueName;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.billing.api.PaymentMethod;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.campus.ClassService;
import com.jey.core.member.StudentService;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.Money;

// 결제 버튼을 두 번 누르거나(멱등 키가 다르다) 결제와 결제 취소가 겹쳐도 청구와 결제가 어긋나지 않는다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
@WithJeyUser(id = PaymentConcurrencyTest.ADMIN_ID, role = UserRole.ADMIN)
class PaymentConcurrencyTest {

	static final long ADMIN_ID = 900_895L;

	private static final AuthenticatedUser ADMIN = new AuthenticatedUser(ADMIN_ID, "admin", "관리자", UserRole.ADMIN,
			null, null);

	private static final int ROUNDS = 10;

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

	@Test
	void 같은_청구를_동시에_두_번_결제하면_하나만_되고_다른_쪽은_409다() throws Exception {
		long cls = newClass();
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			for (int round = 0; round < ROUNDS; round++) {
				InvoiceDetail invoice = newInvoice(cls);

				List<String> outcomes = race(pool, () -> {
					payments.record(invoice.id(), PaymentMethod.CASH, Money.of(280_000), Money.ZERO, ADMIN);
					return "paid";
				}, () -> {
					payments.record(invoice.id(), PaymentMethod.CARD_POS, Money.of(280_000), Money.ZERO, ADMIN);
					return "paid";
				});

				assertThat(outcomes).containsExactlyInAnyOrder("paid", "BILLING_INVOICE_NOT_PAYABLE");
				assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM billing_payment WHERE invoice_id = ?", Long.class,
						invoice.id())).isOne();
				assertThat(jdbc.queryForObject("SELECT status FROM billing_invoice WHERE id = ?", String.class,
						invoice.id())).isEqualTo("PAID");
			}
		}
		finally {
			pool.shutdownNow();
		}
	}

	// 결제 취소와 청구 취소가 겹치면 차례로 돈다: 결제 취소가 먼저면 청구 취소도 되고, 청구 취소가 먼저면(결제가 있어) 409다.
	// 어느 쪽이든 "결제가 유효한데 청구는 취소됨" 같은 어긋난 상태가 남지 않는다.
	@Test
	void 결제_취소와_청구_취소가_겹쳐도_어긋나지_않는다() throws Exception {
		long cls = newClass();
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			for (int round = 0; round < ROUNDS; round++) {
				InvoiceDetail invoice = newInvoice(cls);
				long paymentId = payments.record(invoice.id(), PaymentMethod.CASH, Money.of(280_000), Money.ZERO, ADMIN)
					.paymentId();

				List<String> outcomes = race(pool, () -> {
					payments.cancel(paymentId, ADMIN);
					return "payment-canceled";
				}, () -> {
					invoices.cancel(invoice.id(), ADMIN);
					return "invoice-canceled";
				});

				String invoiceStatus = jdbc.queryForObject("SELECT status FROM billing_invoice WHERE id = ?",
						String.class, invoice.id());
				String paymentStatus = jdbc.queryForObject("SELECT status FROM billing_payment WHERE id = ?",
						String.class, paymentId);
				assertThat(outcomes).isIn(List.of("payment-canceled", "invoice-canceled"),
						List.of("payment-canceled", "BILLING_INVOICE_NOT_CANCELABLE"));
				assertThat(paymentStatus).isEqualTo("CANCELED");
				assertThat(invoiceStatus).isIn("CANCELED", "ISSUED");
			}
		}
		finally {
			pool.shutdownNow();
		}
	}

	// 두 직원이 같은 결제를 동시에 취소한다. 뒤의 요청은 잠금을 기다린 뒤의 최신 결제 상태를 봐야 한다
	// (REPEATABLE READ에서는 잠그지 않는 읽기가 첫 읽기 때의 스냅숏을 본다 — 리뷰 지적). 하나는 취소, 하나는 409, 이벤트 하나.
	@Test
	void 같은_결제를_동시에_두_번_취소하면_하나만_되고_다른_쪽은_409다() throws Exception {
		long cls = newClass();
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			for (int round = 0; round < ROUNDS; round++) {
				InvoiceDetail invoice = newInvoice(cls);
				long paymentId = payments.record(invoice.id(), PaymentMethod.CASH, Money.of(280_000), Money.ZERO, ADMIN)
					.paymentId();

				List<String> outcomes = race(pool, () -> {
					payments.cancel(paymentId, ADMIN);
					return "canceled";
				}, () -> {
					payments.cancel(paymentId, ADMIN);
					return "canceled";
				});

				assertThat(outcomes).containsExactlyInAnyOrder("canceled", "BILLING_PAYMENT_ALREADY_CANCELED");
				assertThat(jdbc.queryForObject("SELECT status FROM billing_invoice WHERE id = ?", String.class,
						invoice.id())).isEqualTo("ISSUED");
			}
		}
		finally {
			pool.shutdownNow();
		}
	}

	// 결제 기록과 청구 취소가 겹친다. 어느 쪽이 먼저든 "취소한 청구에 유효한 결제"는 남지 않는다
	// (결제와 청구 사이에는 테이블을 넘는 DB 제약이 없어 청구 행 잠금이 지킨다).
	@Test
	void 결제_기록과_청구_취소가_겹쳐도_취소한_청구에_유효한_결제가_남지_않는다() throws Exception {
		long cls = newClass();
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			for (int round = 0; round < ROUNDS; round++) {
				InvoiceDetail invoice = newInvoice(cls);

				List<String> outcomes = race(pool, () -> {
					payments.record(invoice.id(), PaymentMethod.CASH, Money.of(280_000), Money.ZERO, ADMIN);
					return "paid";
				}, () -> {
					invoices.cancel(invoice.id(), ADMIN);
					return "invoice-canceled";
				});

				String status = jdbc.queryForObject("SELECT status FROM billing_invoice WHERE id = ?", String.class,
						invoice.id());
				long completed = jdbc.queryForObject(
						"SELECT COUNT(*) FROM billing_payment WHERE invoice_id = ? AND status = 'COMPLETED'", Long.class,
						invoice.id());
				assertThat(outcomes).isIn(List.of("paid", "BILLING_INVOICE_NOT_CANCELABLE"),
						List.of("BILLING_INVOICE_NOT_PAYABLE", "invoice-canceled"));
				assertThat(status + "/" + completed).isIn("PAID/1", "CANCELED/0");
			}
		}
		finally {
			pool.shutdownNow();
		}
	}

	private long newClass() {
		return classes.create(1, "반-" + UUID.randomUUID().toString().substring(0, 8), Money.of(280_000), Money.ZERO)
			.getId();
	}

	private InvoiceDetail newInvoice(long cls) {
		long student = students.register(registration(uniqueName(), mother(randomPhone()))).id();
		return invoices.issue(new InvoiceDraft(student, YearMonth.of(2026, 11),
				List.of(new InvoiceDraft.LineDraft(cls, 8)), List.of()), ADMIN);
	}

	// 두 일을 동시에 시작한다. 결과는 넘긴 순서대로다. 로그인 정보는 스레드마다 다시 넣는다(감사 로그·이벤트가 행위자를 읽는다).
	@SafeVarargs
	private static List<String> race(ExecutorService pool, Callable<String>... tasks) throws Exception {
		var context = SecurityContextHolder.getContext();
		CountDownLatch ready = new CountDownLatch(tasks.length);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<String>> futures = new ArrayList<>();
		for (Callable<String> task : tasks) {
			futures.add(pool.submit(() -> {
				SecurityContextHolder.setContext(context);
				try {
					ready.countDown();
					start.await();
					return task.call();
				}
				catch (BusinessException e) {
					return e.getErrorCode().code();
				}
				finally {
					SecurityContextHolder.clearContext();
				}
			}));
		}
		ready.await(5, TimeUnit.SECONDS);
		start.countDown();
		List<String> outcomes = new ArrayList<>();
		for (Future<String> future : futures) {
			outcomes.add(future.get(30, TimeUnit.SECONDS));
		}
		return outcomes;
	}

}
