package com.jey.dongtanpass.application;

import static com.jey.dongtanpass.DongtanpassFixtures.upsertPartner;
import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
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
import com.jey.billing.api.IssueInvoiceCommand;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.campus.ClassService;
import com.jey.core.member.MemberFixtures;
import com.jey.core.member.StudentService;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.Money;
import com.jey.core.shared.PhoneNumber;
import com.jey.core.shared.PhoneProtector;
import com.jey.dongtanpass.domain.LinkToken;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassNumber;
import com.jey.dongtanpass.domain.PassRepository;
import com.jey.dongtanpass.domain.PaymentMethod;

// 같은 사용 기록으로 다른 달의 청구를 동시에 만든다. 같은 달이면 청구 유니크 키가 막지만, 달이 다르면 사용 기록 잠금만이
// 패스 할인 두 번을 막는다(잠금이 없으면 둘 다 "열린 청구 없음"을 보고 패스 할인이 붙은 청구가 둘 생긴다).
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
@WithJeyUser(id = RedemptionInvoiceConcurrencyTest.STAFF_ID, role = UserRole.STAFF, campusId = 1)
class RedemptionInvoiceConcurrencyTest {

	static final long STAFF_ID = 901_931L;

	private static final AuthenticatedUser STAFF = new AuthenticatedUser(STAFF_ID, "staff", "직원", UserRole.STAFF, 1L,
			null);

	private static final long PARTNER = 901_932L;

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final int ROUNDS = 5;

	@Autowired
	RedemptionInvoiceService redemptionInvoices;

	@Autowired
	PassRedeemService redeemService;

	@Autowired
	PassRepository passes;

	@Autowired
	StudentService students;

	@Autowired
	ClassService classes;

	@Autowired
	PhoneProtector phoneProtector;

	@Autowired
	Clock clock;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void 같은_사용_기록으로_다른_달의_청구를_동시에_만들면_하나만_된다() throws Exception {
		upsertPartner(jdbc, PARTNER, true);
		jdbc.update("UPDATE core_discount_type SET rate = 0.0500, active = TRUE WHERE code = 'DONGTAN_PASS'");
		long cls = classes.create(1, "반-" + UUID.randomUUID().toString().substring(0, 8), Money.of(280_000),
				Money.ZERO).getId();
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			for (int round = 0; round < ROUNDS; round++) {
				Pass pass = newPass();
				long redemptionId = redeemService.redeem(pass.getId(), registerHolderOf(pass), STAFF).redemptionId();
				List<IssueInvoiceCommand.Line> lines = List.of(new IssueInvoiceCommand.Line(cls, 8));

				List<String> outcomes = race(pool, () -> {
					redemptionInvoices.issue(redemptionId, YearMonth.of(2026, 11), lines, List.of(), STAFF);
					return "issued";
				}, () -> {
					redemptionInvoices.issue(redemptionId, YearMonth.of(2026, 12), lines, List.of(), STAFF);
					return "issued";
				});

				assertThat(outcomes).containsExactlyInAnyOrder("issued", "DONGTANPASS_REDEMPTION_ALREADY_INVOICED");
				assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM billing_invoice_line_discount WHERE discount_ref = ?",
						Long.class, pass.getPassNo())).isOne();
				Long attached = jdbc.queryForObject("SELECT invoice_id FROM dongtanpass_redemption WHERE id = ?",
						Long.class, redemptionId);
				assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM billing_invoice i "
						+ "JOIN billing_invoice_line l ON l.invoice_id = i.id "
						+ "JOIN billing_invoice_line_discount d ON d.line_id = l.id WHERE d.discount_ref = ? AND i.id = ?",
						Long.class, pass.getPassNo(), attached)).isOne();
			}
		}
		finally {
			pool.shutdownNow();
		}
	}

	private final java.util.Map<Long, String> phones = new java.util.HashMap<>();

	// 연계 학원이 발급한 패스. 학부모 번호는 학생을 등록할 때 쓴다.
	private Pass newPass() {
		String phone = "0109" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
		Pass pass = passes.saveAndFlush(Pass.issue(PARTNER, PassNumber.generate(RANDOM),
				LinkToken.generate(RANDOM).hash(), MemberFixtures.uniqueName(),
				phoneProtector.fullIndex(PhoneNumber.of(phone)), clock.instant(), SEOUL, LocalDate.now(clock),
				Money.of(150_000), PaymentMethod.CARD));
		phones.put(pass.getId(), phone);
		return pass;
	}

	// 패스의 이름·학부모 번호로 동탄관에 학생을 등록한다(데스크 사용 처리의 앞 단계).
	private long registerHolderOf(Pass pass) {
		return students.register(MemberFixtures.registration(pass.getStudentName(),
				MemberFixtures.mother(PhoneNumber.of(phones.get(pass.getId()))))).id();
	}

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
