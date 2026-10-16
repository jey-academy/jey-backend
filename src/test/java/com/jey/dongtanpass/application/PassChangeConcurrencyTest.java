package com.jey.dongtanpass.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.Money;
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

import static com.jey.core.member.MemberFixtures.randomPhone;
import static com.jey.dongtanpass.DongtanpassFixtures.upsertPartner;
import static org.assertj.core.api.Assertions.assertThat;

// 같은 패스를 고치는 요청(취소, 링크 새로 만들기)이 동시에 와도 행 잠금으로 차례로 돈다.
// 버전 충돌로 서버 오류가 나지 않고, 취소는 한 번만 되며, 취소 뒤의 요청은 409(취소된 패스)다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class PassChangeConcurrencyTest {

	private static final long PARTNER = 900_901L;

	private static final int THREADS = 16;

	@Autowired
	PassService passService;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void setUp() {
		upsertPartner(jdbc, PARTNER, true);
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, id = 900_911L, partnerId = PARTNER)
	void 같은_패스의_취소와_링크_새로_만들기가_동시에_와도_서버_오류가_없고_취소는_한_번이다() throws Exception {
		long passId = passService.issue(PARTNER, new NewPass("김하윤", randomPhone(), LocalDate.of(2026, 10, 1),
				Money.of(150000), PaymentMethod.CARD, true, ConsentNotice.CURRENT.version())).id();
		SecurityContext login = SecurityContextHolder.getContext();
		ExecutorService executor = Executors.newFixedThreadPool(THREADS);
		CountDownLatch ready = new CountDownLatch(THREADS);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<String>> results = new ArrayList<>();
		try {
			for (int i = 0; i < THREADS; i++) {
				boolean cancel = i % 2 == 0;
				results.add(executor.submit(() -> {
					SecurityContextHolder.setContext(login);
					try {
						ready.countDown();
						start.await();
						if (cancel) {
							passService.cancel(PARTNER, passId);
							return "canceled";
						}
						passService.renewLink(PARTNER, passId);
						return "renewed";
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
			for (Future<String> result : results) {
				// 업무 거부가 아닌 예외(버전 충돌, 잠금 실패)는 여기서 ExecutionException으로 드러난다.
				outcomes.add(result.get(30, TimeUnit.SECONDS));
			}

			// 취소가 먼저 잡히면 링크 새로 만들기는 하나도 성공하지 않는다. 나올 수 있는 결과의 범위만 본다.
			assertThat(outcomes).isSubsetOf("canceled", "renewed", "DONGTANPASS_PASS_CANCELED");
			assertThat(outcomes).filteredOn("canceled"::equals).hasSize(1);
		}
		finally {
			executor.shutdownNow();
		}
		assertThat(jdbc.queryForObject("SELECT status FROM dongtanpass_pass WHERE id = ?", String.class, passId))
			.isEqualTo("CANCELED");
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM core_audit_log WHERE action = 'dongtanpass.pass-canceled' AND target_id = ?",
				Integer.class, passId))
			.isEqualTo(1);
	}

}
