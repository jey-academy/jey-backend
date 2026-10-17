package com.jey.dongtanpass.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.member.StudentService;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.Money;
import com.jey.core.shared.PhoneNumber;
import com.jey.dongtanpass.domain.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import static com.jey.core.member.MemberFixtures.mother;
import static com.jey.core.member.MemberFixtures.randomPhone;
import static com.jey.core.member.MemberFixtures.registration;
import static com.jey.core.member.MemberFixtures.uniqueName;
import static com.jey.dongtanpass.DongtanpassFixtures.upsertPartner;
import static org.assertj.core.api.Assertions.assertThat;

// 같은 패스나 같은 학생을 동시에 쓰면 하나만 성공하고 나머지는 상태에 맞는 409다(ADR-0033).
// 사용 처리와 취소·링크 새로 만들기가 겹쳐도 서버 오류가 없고, 패스의 상태와 사용 기록이 서로 맞는다.
// 업무 거부가 아닌 예외(버전 충돌, 유니크 위반 그대로)는 Future#get에서 ExecutionException으로 드러난다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
@WithJeyUser(role = UserRole.STAFF, id = 901_811L, campusId = 1)
class PassRedeemConcurrencyTest {

	private static final long PARTNER = 901_801L;

	private static final int ROUNDS = 10;

	@Autowired
	PassRedeemService redeemService;

	@Autowired
	PassService passService;

	@Autowired
	StudentService studentService;

	@Autowired
	JdbcTemplate jdbc;

	private AuthenticatedUser staff;

	@BeforeEach
	void setUp() {
		upsertPartner(jdbc, PARTNER, true);
		// 사용 처리는 계정마다 센다. 테스트마다 다른 계정으로 부른다(Redis의 횟수는 테스트 사이에 남는다).
		staff = new AuthenticatedUser(9_018_000_000L + ThreadLocalRandom.current().nextInt(1_000_000_000), "desk-staff",
				"데스크", UserRole.STAFF, 1L, null);
	}

	// 모두 패스와 맞는 신규 학생이다. 같은 이름·같은 학부모로는 두 번 등록되지 않으므로, 다른 번호로 등록한 뒤
	// 패스의 번호를 가진 학부모를 DB에 직접 이어 둔다(등록 규칙을 우회한 준비일 뿐, 경합을 만드는 것이 목적이다).
	@Test
	void 같은_패스를_여러_학생에게_동시에_쓰면_하나만_된다() throws Exception {
		PhoneNumber phone = randomPhone();
		String name = uniqueName();
		long passId = issue(name, phone);
		long first = register(name, phone);
		long guardianId = jdbc.queryForObject("SELECT guardian_id FROM core_student_guardian WHERE student_id = ?",
				Long.class, first);
		List<Callable<String>> tasks = new ArrayList<>();
		tasks.add(() -> redeem(passId, first));
		for (int i = 0; i < 5; i++) {
			long studentId = register(name, randomPhone());
			jdbc.update("""
					INSERT INTO core_student_guardian (student_id, guardian_id, relation, created_at, updated_at)
					VALUES (?, ?, 'FATHER', NOW(6), NOW(6))
					""", studentId, guardianId);
			tasks.add(() -> redeem(passId, studentId));
		}

		List<String> outcomes = race(tasks);

		assertThat(outcomes).filteredOn("redeemed"::equals).hasSize(1);
		assertThat(outcomes).filteredOn(outcome -> !"redeemed".equals(outcome))
			.containsOnly("DONGTANPASS_PASS_ALREADY_REDEEMED");
		assertThat(statusOf(passId)).isEqualTo("REDEEMED");
		assertThat(redemptionsOfPass(passId)).isOne();
	}

	@Test
	void 한_학생에게_여러_패스를_동시에_쓰면_하나만_된다() throws Exception {
		PhoneNumber phone = randomPhone();
		String name = uniqueName();
		long studentId = register(name, phone);
		List<Long> passIds = new ArrayList<>();
		List<Callable<String>> tasks = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			long passId = issue(name, phone);
			passIds.add(passId);
			tasks.add(() -> redeem(passId, studentId));
		}

		List<String> outcomes = race(tasks);

		assertThat(outcomes).filteredOn("redeemed"::equals).hasSize(1);
		assertThat(outcomes).filteredOn(outcome -> !"redeemed".equals(outcome))
			.containsOnly("DONGTANPASS_STUDENT_NOT_NEW");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dongtanpass_redemption WHERE student_id = ?",
				Integer.class, studentId))
			.isOne();
		// 진 쪽의 패스는 그대로 쓸 수 있다(사용 처리가 되돌려졌다).
		assertThat(passIds.stream().map(this::statusOf)).containsOnlyOnce("REDEEMED")
			.filteredOn(status -> !"REDEEMED".equals(status))
			.containsOnly("ISSUED");
	}

	// 사용 처리와 취소는 둘 중 하나만 된다. 진 사용 처리는 "취소된 패스", 진 취소는 "이미 사용된 패스"다.
	@Test
	void 사용_처리와_취소가_겹치면_하나만_되고_상태와_기록이_맞다() throws Exception {
		for (int round = 0; round < ROUNDS; round++) {
			PhoneNumber phone = randomPhone();
			String name = uniqueName();
			long passId = issue(name, phone);
			long studentId = register(name, phone);

			List<String> outcomes = race(List.of(() -> redeem(passId, studentId), () -> {
				passService.cancel(PARTNER, passId);
				return "canceled";
			}));

			assertThat(outcomes).as("round %d", round)
				.isIn(List.of("redeemed", "DONGTANPASS_PASS_ALREADY_REDEEMED"),
						List.of("DONGTANPASS_PASS_CANCELED", "canceled"));
			boolean redeemed = "redeemed".equals(outcomes.getFirst());
			assertThat(statusOf(passId)).isEqualTo(redeemed ? "REDEEMED" : "CANCELED");
			assertThat(redemptionsOfPass(passId)).isEqualTo(redeemed ? 1 : 0);
		}
	}

	// 링크 새로 만들기에 진 사용 처리는 패스가 아직 쓸 수 있으므로 "다시 시도"다. 다시 보내면 쓴다.
	@Test
	void 사용_처리와_링크_새로_만들기가_겹쳐도_서버_오류가_없고_다시_보내면_쓴다() throws Exception {
		for (int round = 0; round < ROUNDS; round++) {
			PhoneNumber phone = randomPhone();
			String name = uniqueName();
			long passId = issue(name, phone);
			long studentId = register(name, phone);

			List<String> outcomes = race(List.of(() -> redeem(passId, studentId), () -> {
				passService.renewLink(PARTNER, passId);
				return "renewed";
			}));

			assertThat(outcomes.getFirst()).as("round %d", round)
				.isIn("redeemed", "DONGTANPASS_PASS_CHANGED_CONCURRENTLY");
			assertThat(outcomes.get(1)).as("round %d", round).isIn("renewed", "DONGTANPASS_PASS_ALREADY_REDEEMED");
			if (!"redeemed".equals(outcomes.getFirst())) {
				assertThat(statusOf(passId)).isEqualTo("ISSUED");
				assertThat(redeem(passId, studentId)).isEqualTo("redeemed");
			}
			assertThat(statusOf(passId)).isEqualTo("REDEEMED");
			assertThat(redemptionsOfPass(passId)).isOne();
		}
	}

	private String redeem(long passId, long studentId) {
		try {
			redeemService.redeem(passId, studentId, staff);
			return "redeemed";
		}
		catch (BusinessException ex) {
			return ex.getErrorCode().code();
		}
	}

	// 모든 작업을 한꺼번에 출발시킨다. 결과는 작업 순서대로다. 업무 거부만 결과 문자열로 바뀐다.
	private List<String> race(List<Callable<String>> tasks) throws Exception {
		SecurityContext login = SecurityContextHolder.getContext();
		ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
		CountDownLatch ready = new CountDownLatch(tasks.size());
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<String>> futures = new ArrayList<>();
			for (Callable<String> task : tasks) {
				futures.add(executor.submit(() -> {
					SecurityContextHolder.setContext(login);
					try {
						ready.countDown();
						start.await();
						return task.call();
					}
					catch (BusinessException ex) {
						return ex.getErrorCode().code();
					}
					finally {
						SecurityContextHolder.clearContext();
					}
				}));
			}
			ready.await();
			start.countDown();
			List<String> outcomes = new ArrayList<>();
			for (Future<String> future : futures) {
				outcomes.add(future.get(30, TimeUnit.SECONDS));
			}
			return outcomes;
		}
		finally {
			executor.shutdownNow();
		}
	}

	private long issue(String name, PhoneNumber phone) {
		return passService.issue(PARTNER, new NewPass(name, phone, LocalDate.of(2026, 10, 1), Money.of(150000),
				PaymentMethod.CARD, true, ConsentNotice.CURRENT.version())).id();
	}

	private long register(String name, PhoneNumber guardianPhone) {
		return studentService.register(registration(name, mother(guardianPhone))).id();
	}

	private String statusOf(long passId) {
		return jdbc.queryForObject("SELECT status FROM dongtanpass_pass WHERE id = ?", String.class, passId);
	}

	private int redemptionsOfPass(long passId) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM dongtanpass_redemption WHERE pass_id = ?", Integer.class,
				passId);
	}

}
