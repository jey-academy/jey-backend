package com.jey.dongtanpass.application;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.Money;
import com.jey.core.shared.PhoneNumber;
import com.jey.core.shared.PhoneProtector;
import com.jey.dongtanpass.domain.LinkToken;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassNumber;
import com.jey.dongtanpass.domain.PassRepository;
import com.jey.dongtanpass.domain.PaymentMethod;
import com.jey.dongtanpass.domain.StudentNameKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import static com.jey.core.member.MemberFixtures.randomPhone;
import static com.jey.dongtanpass.DongtanpassFixtures.upsertPartner;
import static org.assertj.core.api.Assertions.assertThat;

// 같은 학원에서 같은 학생의 다른 두 패스, 또는 같은 패스를 동시에 사용 처리하면 하나만 성공한다.
// 같은 패스는 패스 행의 잠금이, 같은 학생의 다른 패스는 (학원, 학생) 유니크 키가 막는다(마지막 방어선).
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
@WithJeyUser(role = UserRole.PARTNER, id = 902_411L, partnerId = 902_401L)
class PartnerRedeemConcurrencyTest {

	private static final long PARTNER = 902_401L;

	private static final int ROUNDS = 10;

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private static final SecureRandom RANDOM = new SecureRandom();

	@Autowired
	PartnerPassService partnerPassService;

	@Autowired
	CampusPassService campusPassService;

	@Autowired
	PassRepository passes;

	@Autowired
	PassBenefits passBenefits;

	@Autowired
	PhoneProtector phoneProtector;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PartnerVerifiedPasses verifiedPasses;

	private AuthenticatedUser partnerAccount;

	@BeforeEach
	void setUp() {
		upsertPartner(jdbc, PARTNER, true);
		// 사용 처리는 계정마다 센다. 테스트마다 다른 계정으로 부른다(Redis의 횟수는 테스트 사이에 남는다).
		partnerAccount = new AuthenticatedUser(9_024_000_000L + ThreadLocalRandom.current().nextInt(1_000_000_000),
				"partner-desk", "연계 학원", UserRole.PARTNER, null, PARTNER);
	}

	@Test
	void 같은_학생의_다른_두_패스를_같은_학원에서_동시에_쓰면_하나만_성공한다() throws Exception {
		for (int round = 0; round < ROUNDS; round++) {
			long student = ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE / 2);
			PhoneNumber phone = randomPhone();
			long first = save(student, phone);
			long second = save(student, phone);

			List<String> outcomes = race(List.of(() -> redeem(first, phone), () -> redeem(second, phone)));

			assertThat(outcomes).containsExactlyInAnyOrder("redeemed", "DONGTANPASS_STUDENT_ALREADY_USED_AT_PARTNER");
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dongtanpass_partner_redemption WHERE student_id = ?",
					Integer.class, student))
				.isEqualTo(1);
			// 진 쪽의 패스는 쓰지 않은 채로 남는다(다른 학원에서 쓸 수 있다).
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dongtanpass_pass WHERE student_id = ? AND status = 'ISSUED'",
					Integer.class, student))
				.isEqualTo(1);
		}
	}

	@Test
	void 같은_패스를_동시에_두_번_쓰면_하나만_성공한다() throws Exception {
		for (int round = 0; round < ROUNDS; round++) {
			PhoneNumber phone = randomPhone();
			long passId = save(ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE / 2), phone);

			List<String> outcomes = race(List.of(() -> redeem(passId, phone), () -> redeem(passId, phone)));

			assertThat(outcomes).containsExactlyInAnyOrder("redeemed", "DONGTANPASS_PASS_ALREADY_REDEEMED");
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dongtanpass_partner_redemption WHERE pass_id = ?",
					Integer.class, passId))
				.isEqualTo(1);
		}
	}

	// JEY 지점의 취소와 연계 학원의 사용 처리는 같은 패스 행을 잠그고 차례로 돈다. 먼저 끝난 쪽이 이기고 진 쪽은 409다
	// (잠금이 빠지면 버전 충돌로 500이 나거나, 취소된 패스에 사용 기록이 남는다).
	@Test
	void JEY_지점의_취소와_연계_학원의_사용_처리가_겹치면_하나만_된다() throws Exception {
		for (int round = 0; round < ROUNDS; round++) {
			PhoneNumber phone = randomPhone();
			long passId = save(ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE / 2), phone);

			List<String> outcomes = race(List.of(() -> redeem(passId, phone), () -> {
				campusPassService.cancel(passId);
				return "canceled";
			}));

			assertThat(outcomes).isIn(List.of("redeemed", "DONGTANPASS_PASS_ALREADY_REDEEMED"),
					List.of("DONGTANPASS_PASS_CANCELED", "canceled"));
			String status = jdbc.queryForObject("SELECT status FROM dongtanpass_pass WHERE id = ?", String.class, passId);
			int records = jdbc.queryForObject("SELECT COUNT(*) FROM dongtanpass_partner_redemption WHERE pass_id = ?",
					Integer.class, passId);
			assertThat(status + "/" + records).isIn("REDEEMED/1", "CANCELED/0");
		}
	}

	private String redeem(long passId, PhoneNumber phone) {
		partnerPassService.redeem(passId, StudentNameKey.of("김하윤"), List.of(phone), Money.of(280000), partnerAccount);
		return "redeemed";
	}

	private long save(long student, PhoneNumber phone) {
		Instant now = Instant.now();
		long id = passes.saveAndFlush(Pass.issueAtCampus(passBenefits.current().campusId(), student,
				PassNumber.generate(RANDOM), LinkToken.generate(RANDOM).hash(), "김하윤", phoneProtector.fullIndex(phone),
				now, SEOUL, LocalDate.ofInstant(now, SEOUL), Money.of(280000), PaymentMethod.CARD,
				new BigDecimal("0.05")))
			.getId();
		// 서비스를 직접 부르므로 확인 단계를 건너뛴다. 이 학원이 확인한 것으로 적어 둔다.
		verifiedPasses.remember(PARTNER, id);
		return id;
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

}
