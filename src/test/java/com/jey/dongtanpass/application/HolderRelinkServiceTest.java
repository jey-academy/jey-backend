package com.jey.dongtanpass.application;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.jey.TestcontainersConfiguration;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.Money;
import com.jey.core.shared.PhoneNumber;
import com.jey.core.shared.PhoneProtector;
import com.jey.core.shared.RateLimitedException;
import com.jey.dongtanpass.domain.LinkToken;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassNumber;
import com.jey.dongtanpass.domain.PassRepository;
import com.jey.dongtanpass.domain.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static com.jey.dongtanpass.DongtanpassFixtures.upsertPartner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

// 학부모가 학생 이름과 전체 번호로 링크를 다시 받는다(ADR-0035 개정). 테스트마다 다른 번호와 접속 주소를 쓴다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class HolderRelinkServiceTest {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private static final long PARTNER = 901_301L;

	private static final String NAME = "김하윤";

	private static final SecureRandom RANDOM = new SecureRandom();

	@Autowired
	HolderRelinkService holderRelink;

	@Autowired
	HolderPassService holderPass;

	@Autowired
	PassRepository passes;

	@Autowired
	PhoneProtector phoneProtector;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	DataSource dataSource;

	@Autowired
	Clock clock;

	private PhoneNumber phone;

	private String address;

	@BeforeEach
	void setUp() {
		upsertPartner(jdbc, PARTNER, true);
		phone = newPhone();
		address = newAddress();
	}

	@Test
	void 맞는_이름과_번호면_새_링크를_주고_옛_링크를_막는다() {
		LinkToken old = LinkToken.generate(RANDOM);
		Pass pass = save(old, NAME, 0);

		RelinkedLink relinked = holderRelink.relink(NAME, phone, address);

		assertThat(LinkToken.isWellFormed(relinked.linkToken())).isTrue();
		assertThat(holderPass.view(relinked.linkToken()).passNo()).isEqualTo(pass.getPassNo());
		assertNotFound(() -> holderPass.view(old.raw()));
	}

	// 붙여 넣은 이름은 자모로 풀리거나 띄어쓰기가 다를 수 있다.
	@Test
	void 이름은_공백과_자모_분리와_무관하게_맞춘다() {
		Pass pass = save(LinkToken.generate(RANDOM), "김 하윤", 0);

		RelinkedLink relinked = holderRelink.relink(" " + Normalizer.normalize("김하　윤", Normalizer.Form.NFD),
				phone, address);

		assertThat(holderPass.view(relinked.linkToken()).passNo()).isEqualTo(pass.getPassNo());
	}

	// 한 번호·이름에 패스가 여럿일 수 있다(두 학원에서 받았거나, 취소하고 다시 받았다).
	@Test
	void 쓸_수_있는_패스가_여럿이면_가장_최근_것만_바꾼다() {
		LinkToken olderLink = LinkToken.generate(RANDOM);
		LinkToken newerLink = LinkToken.generate(RANDOM);
		LinkToken canceledLink = LinkToken.generate(RANDOM);
		Pass older = save(olderLink, NAME, -3);
		Pass newer = save(newerLink, NAME, -1);
		Pass canceled = save(canceledLink, NAME, 0);
		setStatus(canceled, "CANCELED");

		RelinkedLink relinked = holderRelink.relink(NAME, phone, address);

		assertThat(holderPass.view(relinked.linkToken()).passNo()).isEqualTo(newer.getPassNo());
		assertNotFound(() -> holderPass.view(newerLink.raw()));
		assertThat(holderPass.view(olderLink.raw()).passNo()).isEqualTo(older.getPassNo());
		assertThat(holderPass.view(canceledLink.raw()).passNo()).isEqualTo(canceled.getPassNo());
	}

	// 만료는 저장하지 않고 오늘로 계산한다. 만료일까지 쓸 수 있다.
	@Test
	void 만료일_당일은_다시_받고_다음_날은_만료로_거부한다() {
		Pass today = save(LinkToken.generate(RANDOM), NAME, 0);
		setExpiresOn(today, today());

		assertThat(holderRelink.relink(NAME, phone, address).linkToken()).isNotBlank();

		setExpiresOn(today, today().minusDays(1));
		assertDenied(() -> holderRelink.relink(NAME, phone, address), DongtanpassErrorCode.PASS_EXPIRED);
	}

	@ParameterizedTest(name = "{0} → {1}")
	@CsvSource({ "REDEEMED,PASS_ALREADY_REDEEMED", "CANCELED,PASS_CANCELED" })
	void 쓸_수_없는_패스면_그_사정으로_거부하고_링크를_바꾸지_않는다(String status, DongtanpassErrorCode code) {
		LinkToken link = LinkToken.generate(RANDOM);
		Pass pass = save(link, NAME, 0);
		setStatus(pass, status);

		assertDenied(() -> holderRelink.relink(NAME, phone, address), code);

		assertThat(passes.findById(pass.getId()).orElseThrow().getLinkTokenHash()).isEqualTo(link.hash());
	}

	// 쓸 수 있는 것이 없으면 가장 최근에 발급된 패스의 사정으로 답한다.
	@Test
	void 쓸_수_있는_것이_없으면_가장_최근_패스의_사정으로_거부한다() {
		Pass olderCanceled = save(LinkToken.generate(RANDOM), NAME, -5);
		setStatus(olderCanceled, "CANCELED");
		Pass newerRedeemed = save(LinkToken.generate(RANDOM), NAME, -1);
		setStatus(newerRedeemed, "REDEEMED");

		assertDenied(() -> holderRelink.relink(NAME, phone, address), DongtanpassErrorCode.PASS_ALREADY_REDEEMED);
	}

	@Test
	void 이름이나_번호가_틀리면_찾을_수_없다() {
		save(LinkToken.generate(RANDOM), NAME, 0);

		assertNotFound(() -> holderRelink.relink("김하은", phone, address));
		assertNotFound(() -> holderRelink.relink(NAME, newPhone(), address));
	}

	// 맞은 요청은 세지 않는다. 학부모가 몇 번 다시 받아도 그 뒤의 틀린 시도 한도가 줄지 않는다.
	@Test
	void 맞은_요청은_횟수에서_되돌린다() {
		Pass canceled = save(LinkToken.generate(RANDOM), "박서준", 0);
		setStatus(canceled, "CANCELED");
		save(LinkToken.generate(RANDOM), NAME, 0);
		for (int i = 0; i < 3; i++) {
			holderRelink.relink(NAME, phone, address);
			assertDenied(() -> holderRelink.relink("박서준", phone, address), DongtanpassErrorCode.PASS_CANCELED);
		}

		for (int i = 0; i < 10; i++) {
			assertNotFound(() -> holderRelink.relink("틀린이름", phone, address));
		}

		RateLimitedException blocked = catchThrowableOfType(RateLimitedException.class,
				() -> holderRelink.relink("틀린이름", phone, address));
		assertThat(blocked).isNotNull();
		assertThat(blocked.getErrorCode()).isEqualTo(DongtanpassErrorCode.HOLDER_RELINK_LIMITED);
		assertThat(blocked.getRetryAfter()).isPositive().isLessThanOrEqualTo(Duration.ofMinutes(5));
	}

	// 막힌 동안은 맞는 이름과 번호도 확인하지 않는다(확인해 주면 한도가 대입을 막지 못한다).
	@Test
	void 막힌_동안은_맞는_이름도_막는다() {
		save(LinkToken.generate(RANDOM), NAME, 0);
		for (int i = 0; i < 10; i++) {
			assertNotFound(() -> holderRelink.relink("틀린이름", phone, address));
		}

		assertThat(catchThrowableOfType(RateLimitedException.class, () -> holderRelink.relink(NAME, phone, address)))
			.isNotNull();
		assertThat(holderRelink.relink(NAME, phone, newAddress()).linkToken()).isNotBlank();
	}

	@Test
	void 한_일과_거부를_감사_로그에_남기고_이름과_번호는_싣지_않는다() {
		Pass pass = save(LinkToken.generate(RANDOM), NAME, 0);
		long limitedBefore = auditCount("dongtanpass.holder-relink-limited", null);

		holderRelink.relink(NAME, phone, address);
		setStatus(pass, "CANCELED");
		assertDenied(() -> holderRelink.relink(NAME, phone, address), DongtanpassErrorCode.PASS_CANCELED);
		for (int i = 0; i < 10; i++) {
			assertNotFound(() -> holderRelink.relink("틀린이름", phone, address));
		}
		for (int i = 0; i < 3; i++) {
			catchThrowableOfType(RateLimitedException.class, () -> holderRelink.relink("틀린이름", phone, address));
		}

		assertThat(auditCount("dongtanpass.holder-link-renewed", pass.getId())).isEqualTo(1);
		assertThat(auditOutcome("dongtanpass.holder-link-renewed", pass.getId())).isEqualTo("DONE");
		assertThat(auditCount("dongtanpass.holder-link-denied", pass.getId())).isEqualTo(1);
		assertThat(auditOutcome("dongtanpass.holder-link-denied", pass.getId())).isEqualTo("DENIED");
		assertThat(auditDetail("dongtanpass.holder-link-denied", pass.getId())).contains("PASS_CANCELED");
		// 처음 막힌 한 번만 남는다
		assertThat(auditCount("dongtanpass.holder-relink-limited", null) - limitedBefore).isEqualTo(1);
		String digits = phone.value().replaceAll("[^0-9]", "");
		List<String> details = jdbc.queryForList(
				"SELECT COALESCE(detail_json, '') FROM core_audit_log WHERE action LIKE 'dongtanpass.holder-%'",
				String.class);
		assertThat(details).allSatisfy(detail -> assertThat(detail).doesNotContain(NAME, digits));
	}

	// 두 보호자가 동시에 다시 받으면 둘 다 링크를 받지만, 마지막에 저장된 하나만 열린다.
	@Test
	void 동시에_같은_패스를_다시_받으면_마지막_토큰만_열린다() throws Exception {
		Pass pass = save(LinkToken.generate(RANDOM), NAME, 0);
		int requests = 2;
		ExecutorService executor = Executors.newFixedThreadPool(requests);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<RelinkedLink>> results = new ArrayList<>();
		try {
			for (int i = 0; i < requests; i++) {
				String from = newAddress();
				results.add(executor.submit(() -> {
					start.await();
					return holderRelink.relink(NAME, phone, from);
				}));
			}
			start.countDown();
			List<String> hashes = new ArrayList<>();
			for (Future<RelinkedLink> result : results) {
				hashes.add(LinkToken.hashOf(result.get(30, TimeUnit.SECONDS).linkToken()));
			}

			assertThat(hashes).doesNotHaveDuplicates();
			assertThat(passes.findById(pass.getId()).orElseThrow().getLinkTokenHash()).isIn(hashes);
		}
		finally {
			executor.shutdownNow();
		}
	}

	// 고른 뒤 잠그기 전에 데스크가 그 패스를 썼다. 잠근 뒤에 다시 보고 사용됨으로 거부해야 한다(링크를 바꾸지 않는다).
	// 다른 연결이 행을 잠근 채 사용 처리하고, 다시 받기가 그 잠금을 기다리는 것을 확인한 뒤 커밋한다.
	@Test
	void 고른_뒤_잠그기_전에_쓰인_패스는_잠근_뒤에_다시_보고_거부한다() throws Exception {
		LinkToken link = LinkToken.generate(RANDOM);
		Pass pass = save(link, NAME, 0);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try (Connection desk = dataSource.getConnection()) {
			desk.setAutoCommit(false);
			try (PreparedStatement lock = desk.prepareStatement(
					"UPDATE dongtanpass_pass SET status = 'REDEEMED' WHERE id = ?")) {
				lock.setLong(1, pass.getId());
				assertThat(lock.executeUpdate()).isEqualTo(1);
			}
			Future<BusinessException> relink = executor.submit(
					() -> catchThrowableOfType(BusinessException.class, () -> holderRelink.relink(NAME, phone, address)));
			awaitLockWait();
			desk.commit();

			BusinessException denial = relink.get(30, TimeUnit.SECONDS);
			assertThat(denial).isNotNull();
			assertThat(denial.getErrorCode()).isEqualTo(DongtanpassErrorCode.PASS_ALREADY_REDEEMED);
		}
		finally {
			executor.shutdownNow();
		}
		assertThat(passes.findById(pass.getId()).orElseThrow().getLinkTokenHash()).isEqualTo(link.hash());
	}

	// 다시 받기의 잠금 조회가 다른 연결의 잠금을 기다리기 시작할 때까지 기다린다.
	private void awaitLockWait() throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
		while (System.nanoTime() < deadline) {
			// 테스트 계정은 잠금 대기 표를 볼 권한이 없다. 같은 계정의 연결은 보이므로, 잠금 조회를 실행 중인 다른 연결을 찾는다.
			Integer waiting = jdbc.queryForObject("""
					SELECT COUNT(*) FROM information_schema.PROCESSLIST
					WHERE ID <> CONNECTION_ID() AND COMMAND = 'Query'
					  AND INFO LIKE '%dongtanpass_pass%' AND INFO LIKE '%for update%'
					""", Integer.class);
			if (waiting != null && waiting > 0) {
				return;
			}
			Thread.sleep(20);
		}
		throw new AssertionError("다시 받기가 잠금을 기다리지 않았다");
	}

	// 발급 시각을 하루 단위로 옮겨 순서를 만든다. 만료일은 발급일 + 1개월이다.
	private Pass save(LinkToken link, String name, int issuedDaysFromToday) {
		return passes.saveAndFlush(Pass.issue(PARTNER, PassNumber.generate(RANDOM), link.hash(), name,
				phoneProtector.fullIndex(phone), clock.instant().plus(Duration.ofDays(issuedDaysFromToday)), SEOUL,
				today(), Money.of(150000), PaymentMethod.CARD));
	}

	private void setStatus(Pass pass, String status) {
		jdbc.update("UPDATE dongtanpass_pass SET status = ? WHERE id = ?", status, pass.getId());
	}

	private void setExpiresOn(Pass pass, LocalDate expiresOn) {
		jdbc.update("UPDATE dongtanpass_pass SET expires_on = ? WHERE id = ?", expiresOn, pass.getId());
	}

	private long auditCount(String action, Long passId) {
		if (passId == null) {
			return jdbc.queryForObject("SELECT COUNT(*) FROM core_audit_log WHERE action = ?", Long.class, action);
		}
		return jdbc.queryForObject("SELECT COUNT(*) FROM core_audit_log WHERE action = ? AND target_id = ?",
				Long.class, action, passId);
	}

	private String auditOutcome(String action, long passId) {
		return jdbc.queryForObject("SELECT outcome FROM core_audit_log WHERE action = ? AND target_id = ?",
				String.class, action, passId);
	}

	private String auditDetail(String action, long passId) {
		return jdbc.queryForObject("SELECT detail_json FROM core_audit_log WHERE action = ? AND target_id = ?",
				String.class, action, passId);
	}

	private static void assertNotFound(Runnable call) {
		assertDenied(call, DongtanpassErrorCode.PASS_NOT_FOUND);
	}

	private static void assertDenied(Runnable call, DongtanpassErrorCode code) {
		BusinessException denial = catchThrowableOfType(BusinessException.class, call::run);
		assertThat(denial).as("거부되어야 한다: " + code).isNotNull();
		assertThat(denial).isNotInstanceOf(RateLimitedException.class);
		assertThat(denial.getErrorCode()).isEqualTo(code);
	}

	private LocalDate today() {
		return LocalDate.now(clock);
	}

	// 010-9로 시작하는 무작위 번호. 테스트마다 다른 번호라 다른 테스트의 패스가 후보에 섞이지 않는다.
	private static PhoneNumber newPhone() {
		return PhoneNumber.of("0109" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000)));
	}

	private static String newAddress() {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		return "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(1, 255);
	}

}
