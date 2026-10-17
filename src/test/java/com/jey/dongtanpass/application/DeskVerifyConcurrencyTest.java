package com.jey.dongtanpass.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.jey.TestcontainersConfiguration;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.Money;
import com.jey.dongtanpass.domain.LinkToken;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassNumber;
import com.jey.dongtanpass.domain.PassRepository;
import com.jey.dongtanpass.domain.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static com.jey.dongtanpass.DongtanpassFixtures.upsertPartner;
import static org.assertj.core.api.Assertions.assertThat;

// 캡처한 QR을 두 데스크에서 동시에 읽어도 한 번만 통과한다(토큰 ID를 Redis에 SET NX로 적는다).
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class DeskVerifyConcurrencyTest {

	private static final long PARTNER = 901_501L;

	private static final int THREADS = 20;

	private static final SecureRandom RANDOM = new SecureRandom();

	@Autowired
	DeskPassService deskPassService;

	@Autowired
	PassRepository passes;

	@Autowired
	PassQrCodes passQrCodes;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Clock clock;

	@BeforeEach
	void setUp() {
		upsertPartner(jdbc, PARTNER, true);
	}

	@Test
	void 같은_QR_토큰을_동시에_확인하면_하나만_통과하고_나머지는_무효다() throws Exception {
		Pass pass = passes.saveAndFlush(Pass.issue(PARTNER, PassNumber.generate(RANDOM), LinkToken.generate(RANDOM).hash(),
				"김하윤", "phone-index", clock.instant(), ZoneId.of("Asia/Seoul"), LocalDate.now(clock),
				Money.of(150000), PaymentMethod.CARD));
		String token = passQrCodes.issue(pass.getId(), clock.instant()).token();
		AuthenticatedUser staff = new AuthenticatedUser(901_510L, "desk-staff", "데스크", UserRole.STAFF, 1L, null);
		ExecutorService executor = Executors.newFixedThreadPool(THREADS);
		CountDownLatch ready = new CountDownLatch(THREADS);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<String>> results = new ArrayList<>();
		try {
			for (int i = 0; i < THREADS; i++) {
				results.add(executor.submit(() -> {
					ready.countDown();
					start.await();
					try {
						return "passId=" + deskPassService.verifyQr(token, staff).passId();
					}
					catch (BusinessException ex) {
						return ex.getErrorCode().code();
					}
				}));
			}
			ready.await();
			start.countDown();
			List<String> outcomes = new ArrayList<>();
			for (Future<String> result : results) {
				// 업무 거부가 아닌 예외는 여기서 ExecutionException으로 드러난다.
				outcomes.add(result.get(30, TimeUnit.SECONDS));
			}

			assertThat(outcomes).filteredOn(("passId=" + pass.getId())::equals).hasSize(1);
			assertThat(outcomes).filteredOn("DONGTANPASS_QR_INVALID"::equals).hasSize(THREADS - 1);
		}
		finally {
			executor.shutdownNow();
		}
	}

}
