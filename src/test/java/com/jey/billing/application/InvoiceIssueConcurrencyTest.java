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
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.campus.ClassService;
import com.jey.core.member.StudentService;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.Money;

// 데스크에서 버튼을 두 번 누르거나 두 직원이 같은 학생을 동시에 처리해도(멱등 키가 다르다) 그 달 청구는 하나다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
@WithJeyUser(id = InvoiceIssueConcurrencyTest.ADMIN_ID, role = UserRole.ADMIN)
class InvoiceIssueConcurrencyTest {

	static final long ADMIN_ID = 900_881L;

	private static final AuthenticatedUser ADMIN = new AuthenticatedUser(ADMIN_ID, "admin", "관리자", UserRole.ADMIN,
			null, null);

	private static final int ROUNDS = 10;

	@Autowired
	InvoiceService invoices;

	@Autowired
	StudentService students;

	@Autowired
	ClassService classes;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void 같은_학생의_같은_달을_동시에_만들면_하나만_되고_다른_쪽은_409다() throws Exception {
		long cls = classes.create(1, "반-" + UUID.randomUUID().toString().substring(0, 8), Money.of(280_000),
				Money.ZERO).getId();
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			for (int round = 0; round < ROUNDS; round++) {
				long student = students.register(registration(uniqueName(), mother(randomPhone()))).id();
				InvoiceDraft draft = new InvoiceDraft(student, YearMonth.of(2026, 11),
						List.of(new InvoiceDraft.LineDraft(cls, 8)), List.of());

				List<String> outcomes = race(pool, () -> {
					invoices.issue(draft, ADMIN);
					return "issued";
				});

				assertThat(outcomes).containsExactlyInAnyOrder("issued", "BILLING_INVOICE_ALREADY_EXISTS");
				assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM billing_invoice WHERE student_id = ?",
						Long.class, student)).isOne();
			}
		}
		finally {
			pool.shutdownNow();
		}
	}

	// 두 스레드가 같은 일을 동시에 시작한다. 로그인 정보는 스레드마다 다시 넣는다(감사 로그가 행위자를 읽는다).
	private static List<String> race(ExecutorService pool, Callable<String> task) throws Exception {
		var context = SecurityContextHolder.getContext();
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<String>> futures = new ArrayList<>();
		for (int i = 0; i < 2; i++) {
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
