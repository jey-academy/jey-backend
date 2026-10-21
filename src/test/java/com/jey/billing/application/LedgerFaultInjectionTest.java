package com.jey.billing.application;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static com.jey.core.member.MemberFixtures.DONGTAN;
import static com.jey.core.member.MemberFixtures.mother;
import static com.jey.core.member.MemberFixtures.randomPhone;
import static com.jey.core.member.MemberFixtures.uniqueName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.jey.WithJeyUser;
import com.jey.billing.LedgerSheetsTestSupport;
import com.jey.billing.api.PaymentMethod;
import com.jey.billing.domain.LedgerSync;
import com.jey.billing.domain.LedgerSyncReason;
import com.jey.billing.domain.LedgerSyncRepository;
import com.jey.billing.domain.LedgerSyncStatus;
import com.jey.billing.domain.PaymentStatus;
import com.jey.billing.domain.ledger.LedgerFormats;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.campus.ClassService;
import com.jey.core.member.StudentRegistration;
import com.jey.core.member.StudentService;
import com.jey.core.member.domain.Grade;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.Money;

/**
 * 구글 장애를 흉내 내 장부 기록이 결제를 깨지 않고, 두 번 쓰지 않고, 장애가 풀리면 다시 들어가는지 본다(이슈 #89의 목록).
 * 시나리오마다 시도 횟수, 미반영 건수, 두 번 쓴 건수를 모아 끝에 표로 찍는다.
 */
@WithJeyUser(id = LedgerFaultInjectionTest.ADMIN_ID, role = UserRole.ADMIN)
class LedgerFaultInjectionTest extends LedgerSheetsTestSupport {

	static final long ADMIN_ID = 900_897L;

	private static final AuthenticatedUser ADMIN = new AuthenticatedUser(ADMIN_ID, "admin", "관리자", UserRole.ADMIN,
			null, null);

	private static final Map<String, String> REPORT = new LinkedHashMap<>();

	private static final Pattern MARKER = Pattern.compile("\"앱:결제-(\\d+)\"");

	@Autowired
	PaymentService payments;

	@Autowired
	InvoiceService invoices;

	@Autowired
	StudentService students;

	@Autowired
	ClassService classes;

	@Autowired
	LedgerSyncService ledger;

	@Autowired
	LedgerSyncRepository syncs;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Clock clock;

	@Test
	void 응답이_늦으면_간격을_두고_다시_하고_끝내_실패하면_미반영이고_결제는_그대로다() {
		exhausts("지연(읽기 시간 초과)", okJson("{\"values\": []}").withFixedDelay(1_500));
	}

	@Test
	void 구글이_5xx로_답하면_간격을_두고_다시_하고_끝내_실패하면_미반영이다() {
		exhausts("5xx", aResponse().withStatus(503));
	}

	@Test
	void 연결이_끊기면_간격을_두고_다시_하고_끝내_실패하면_미반영이다() {
		exhausts("연결 끊김", aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER));
	}

	@Test
	void 요청_한도에_걸리면_구글이_알려_준_시간만큼_기다린다() {
		String className = newClassName();
		google.stubFor(get(urlPathEqualTo(READ_PATH))
			.willReturn(aResponse().withStatus(429).withHeader("Retry-After", "600")));

		long paymentId = pay(className);

		LedgerSync waiting = awaitAttempts(paymentId, 1);
		assertThat(waiting.getStatus()).isEqualTo(LedgerSyncStatus.PENDING);
		assertThat(waiting.getNextAttemptAt()).isAfter(clock.instant().plusSeconds(590));
		int attempts = drive(paymentId);
		assertThat(sync(paymentId).getStatus()).isEqualTo(LedgerSyncStatus.UNSYNCED);
		assertThat(paymentStatus(paymentId)).isEqualTo(PaymentStatus.COMPLETED);
		report("429(Retry-After 600초)", attempts, 1, doubleWrites());
	}

	// 쓰기 요청은 구글에 닿아 칸이 채워졌는데 응답을 받기 전에 연결이 끊겼다. 다음 시도는 탭에서 이 결제의 표시를 찾아 쓰지 않는다.
	@Test
	void 쓰기는_됐는데_응답을_잃어도_다시_시도할_때_표시를_찾아_한_번만_쓴다() {
		String className = newClassName();
		String scenario = "응답 잃음";
		google.stubFor(get(urlPathEqualTo(READ_PATH)).inScenario(scenario).whenScenarioStateIs(STARTED)
			.willReturn(okJson(json(tab(className, List.of(empty(), empty()))))));
		google.stubFor(post(urlPathEqualTo(WRITE_PATH)).inScenario(scenario).whenScenarioStateIs(STARTED)
			.willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))
			.willSetStateTo("written"));

		long paymentId = pay(className);
		awaitAttempts(paymentId, 1);
		google.stubFor(get(urlPathEqualTo(READ_PATH)).inScenario(scenario).whenScenarioStateIs("written")
			.willReturn(okJson(json(tab(className,
					List.of(written("하하하", className, LedgerFormats.marker(paymentId)), empty()))))));
		int attempts = drive(paymentId);

		LedgerSync sync = sync(paymentId);
		assertThat(sync.getStatus()).isEqualTo(LedgerSyncStatus.SYNCED);
		assertThat(sync.getSheetRow()).isEqualTo(6);
		google.verify(1, postRequestedFor(urlPathEqualTo(WRITE_PATH)));
		report("쓰기 뒤 응답 잃음", attempts, 0, doubleWrites());
	}

	@Test
	void 같은_결제_완료를_여러_번_동시에_받아도_한_번만_쓴다() throws Exception {
		String className = newClassName();
		stubTab(className);
		stubWriteOk();
		long paymentId = pay(className);

		ExecutorService pool = Executors.newFixedThreadPool(3);
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<?>> runs = new ArrayList<>();
			for (int i = 0; i < 3; i++) {
				runs.add(pool.submit(() -> {
					start.await();
					ledger.recordPayment(paymentId);
					return null;
				}));
			}
			start.countDown();
			for (Future<?> run : runs) {
				run.get(30, TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdownNow();
		}

		awaitStatus(paymentId, LedgerSyncStatus.SYNCED);
		assertThat(syncs.findAll().stream().filter(s -> s.getPaymentId() == paymentId)).hasSize(1);
		google.verify(1, postRequestedFor(urlPathEqualTo(WRITE_PATH)));
		report("같은 이벤트 재전송(동시 4번)", sync(paymentId).getAttempts(), 0, doubleWrites());
	}

	// 첫 시도가 쓰지 못한 사이 사람이 그 빈 줄(6행)을 채웠다. 다음 시도는 그 줄을 덮어쓰지 않고 다음 빈 줄(7행)에 쓴다.
	@Test
	void 앞선_시도가_고른_줄을_그사이_사람이_채우면_덮어쓰지_않고_다음_빈_줄에_쓴다() {
		String className = newClassName();
		String scenario = "사람이 채움";
		google.stubFor(get(urlPathEqualTo(READ_PATH)).inScenario(scenario).whenScenarioStateIs(STARTED)
			.willReturn(okJson(json(tab(className, List.of(empty(), empty()))))));
		google.stubFor(post(urlPathEqualTo(WRITE_PATH)).inScenario(scenario).whenScenarioStateIs(STARTED)
			.willReturn(aResponse().withStatus(503))
			.willSetStateTo("human"));
		google.stubFor(get(urlPathEqualTo(READ_PATH)).inScenario(scenario).whenScenarioStateIs("human")
			.willReturn(okJson(json(tab(className, List.of(filled("사사사", className), empty()))))));
		google.stubFor(post(urlPathEqualTo(WRITE_PATH)).inScenario(scenario).whenScenarioStateIs("human")
			.willReturn(okJson("{}")));

		long paymentId = pay(className);
		awaitAttempts(paymentId, 1);
		int attempts = drive(paymentId);

		assertThat(sync(paymentId).getSheetRow()).isEqualTo(7);
		// 쓰기 요청은 둘: 실패한 6행, 그다음 7행. 6행에 성공한 쓰기는 없다.
		google.verify(2, postRequestedFor(urlPathEqualTo(WRITE_PATH)));
		google.verify(1, postRequestedFor(urlPathEqualTo(WRITE_PATH)).withRequestBody(containing("'2026-11'!D7")));
		report("사람이 그 줄을 채움", attempts, 0, doubleWrites());
	}

	// 장부는 쓰면 바뀐다: 첫 쓰기(6행) 뒤에는 6행이 찬 탭을 돌려준다. 잠금이 없으면 두 결제가 같은 탭을 읽고 둘 다 6행에 쓴다.
	@Test
	void 두_결제가_같은_반_블록에_동시에_들어와도_서로_다른_줄에_쓴다() throws Exception {
		String className = newClassName();
		String scenario = "동시 결제";
		google.stubFor(get(urlPathEqualTo(READ_PATH)).inScenario(scenario).whenScenarioStateIs(STARTED)
			.willReturn(okJson(json(tab(className, List.of(empty(), empty(), empty())))).withFixedDelay(200)));
		google.stubFor(post(urlPathEqualTo(WRITE_PATH)).inScenario(scenario).whenScenarioStateIs(STARTED)
			.willReturn(okJson("{}"))
			.willSetStateTo("row6"));
		google.stubFor(get(urlPathEqualTo(READ_PATH)).inScenario(scenario).whenScenarioStateIs("row6")
			.willReturn(okJson(json(tab(className, List.of(filled("먼저", className), empty(), empty()))))));
		google.stubFor(post(urlPathEqualTo(WRITE_PATH)).inScenario(scenario).whenScenarioStateIs("row6")
			.willReturn(okJson("{}")));
		long cls = classes.create(DONGTAN, className, Money.of(280_000), Money.ZERO).getId();
		long firstInvoice = invoice(cls);
		long secondInvoice = invoice(cls);

		ExecutorService pool = Executors.newFixedThreadPool(2);
		List<Long> paid = new ArrayList<>();
		try {
			var context = org.springframework.security.core.context.SecurityContextHolder.getContext();
			CountDownLatch start = new CountDownLatch(1);
			List<Future<Long>> runs = new ArrayList<>();
			for (long invoiceId : List.of(firstInvoice, secondInvoice)) {
				runs.add(pool.submit(() -> {
					org.springframework.security.core.context.SecurityContextHolder.setContext(context);
					try {
						start.await();
						return payments.record(invoiceId, PaymentMethod.CASH, Money.of(280_000), Money.ZERO, ADMIN)
							.paymentId();
					} finally {
						org.springframework.security.core.context.SecurityContextHolder.clearContext();
					}
				}));
			}
			start.countDown();
			for (Future<Long> run : runs) {
				paid.add(run.get(30, TimeUnit.SECONDS));
			}
		} finally {
			pool.shutdownNow();
		}

		paid.forEach(id -> awaitStatus(id, LedgerSyncStatus.SYNCED));
		assertThat(paid.stream().map(id -> sync(id).getSheetRow())).containsExactlyInAnyOrder(6, 7);
		google.verify(1, postRequestedFor(urlPathEqualTo(WRITE_PATH)).withRequestBody(containing("'2026-11'!D6")));
		google.verify(1, postRequestedFor(urlPathEqualTo(WRITE_PATH)).withRequestBody(containing("'2026-11'!D7")));
		report("두 결제 동시(같은 블록)", paid.stream().mapToInt(id -> sync(id).getAttempts()).sum(), 0, doubleWrites());
	}

	@Test
	void 장애가_풀린_뒤_관리자가_다시_반영하면_빠진_결제가_모두_들어간다() {
		String className = newClassName();
		google.stubFor(get(urlPathEqualTo(READ_PATH)).willReturn(aResponse().withStatus(503)));
		List<Long> paid = new ArrayList<>();
		long cls = classes.create(DONGTAN, className, Money.of(280_000), Money.ZERO).getId();
		for (int i = 0; i < 3; i++) {
			paid.add(payments.record(invoice(cls), PaymentMethod.CASH, Money.of(280_000), Money.ZERO, ADMIN).paymentId());
		}
		int attempts = 0;
		for (long id : paid) {
			awaitAttempts(id, 1);
			attempts += drive(id);
		}
		assertThat(paid).allSatisfy(id -> assertThat(sync(id).getReason()).isEqualTo(LedgerSyncReason.GOOGLE_UNAVAILABLE));

		google.resetAll();
		String scenario = "복구";
		List<List<String>> rows = new ArrayList<>(List.of(empty(), empty(), empty(), empty()));
		google.stubFor(get(urlPathEqualTo(READ_PATH)).inScenario(scenario).whenScenarioStateIs(STARTED)
			.willReturn(okJson(json(tab(className, rows)))));
		for (int i = 0; i < 3; i++) {
			String next = "w" + (i + 1);
			List<List<String>> after = new ArrayList<>(rows);
			for (int j = 0; j <= i; j++) {
				after.set(j, filled("앞" + j, className));
			}
			google.stubFor(post(urlPathEqualTo(WRITE_PATH)).inScenario(scenario)
				.whenScenarioStateIs(i == 0 ? STARTED : "w" + i)
				.willReturn(okJson("{}"))
				.willSetStateTo(next));
			google.stubFor(get(urlPathEqualTo(READ_PATH)).inScenario(scenario).whenScenarioStateIs(next)
				.willReturn(okJson(json(tab(className, after)))));
		}
		for (long id : paid) {
			assertThat(ledger.retryUnsynced(sync(id).getId()).status()).isEqualTo(LedgerSyncStatus.SYNCED);
		}

		assertThat(paid.stream().map(id -> sync(id).getSheetRow())).containsExactly(6, 7, 8);
		assertThatThrownBy(() -> ledger.retryUnsynced(sync(paid.getFirst()).getId()))
			.isInstanceOfSatisfying(BusinessException.class,
					e -> assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.LEDGER_SYNC_NOT_RETRYABLE));
		report("장애 뒤 다시 반영(3건)", attempts + 3, 0, doubleWrites());
	}

	@AfterAll
	static void printReport() {
		StringBuilder table = new StringBuilder("\n| 시나리오 | 시도 횟수 | 미반영 | 두 번 쓴 결제 |\n|---|---|---|---|\n");
		REPORT.forEach((name, row) -> table.append("| ").append(name).append(" | ").append(row).append(" |\n"));
		System.out.println(table);
	}

	// 같은 응답을 계속 돌려주는 장애. 정한 간격을 다 쓰면(6번 시도) 미반영이고 결제는 그대로다.
	private void exhausts(String name, ResponseDefinitionBuilder fault) {
		String className = newClassName();
		google.stubFor(get(urlPathEqualTo(READ_PATH)).willReturn(fault));

		long paymentId = pay(className);
		awaitAttempts(paymentId, 1);
		int attempts = drive(paymentId);

		LedgerSync sync = sync(paymentId);
		assertThat(sync.getStatus()).isEqualTo(LedgerSyncStatus.UNSYNCED);
		assertThat(sync.getReason()).isEqualTo(LedgerSyncReason.GOOGLE_UNAVAILABLE);
		assertThat(attempts).isEqualTo(LedgerSync.RETRY_DELAYS.size() + 1);
		assertThat(paymentStatus(paymentId)).isEqualTo(PaymentStatus.COMPLETED);
		google.verify(0, postRequestedFor(urlPathEqualTo(WRITE_PATH)));
		report(name, attempts, 1, doubleWrites());
	}

	// 대기 중인 줄의 다음 시도 시각을 지금으로 당기고 장부 잠금도 풀린 것으로 하며(시간이 흐른 것처럼) 끝날 때까지 시도한다.
	// 마지막 시도 횟수를 돌려준다.
	private int drive(long paymentId) {
		for (int round = 0; round < 20; round++) {
			LedgerSync sync = sync(paymentId);
			if (sync.getStatus() != LedgerSyncStatus.PENDING) {
				return sync.getAttempts();
			}
			jdbc.update("UPDATE billing_ledger_sync SET next_attempt_at = ? WHERE id = ?",
					Timestamp.from(clock.instant().minusSeconds(1)), sync.getId());
			expireLedgerLock();
			ledger.attempt(sync.getId());
		}
		throw new AssertionError("끝나지 않았다");
	}

	// 결제마다 표시를 실어 반영된 쓰기가 둘 이상이면 두 번 쓴 것이다. 반영된 쓰기는 2xx로 답했거나, 받은 뒤 연결이 끊긴 요청이다
	// (거절된 쓰기 — 4xx·5xx — 는 시트를 바꾸지 않았다). 취소 표시는 세지 않는다(따옴표로 끝나는 표시만 센다).
	private static long doubleWrites() {
		Map<String, Integer> writes = new LinkedHashMap<>();
		for (ServeEvent event : google.getAllServeEvents()) {
			boolean write = event.getRequest().getUrl().startsWith(WRITE_PATH);
			boolean applied = event.getResponseDefinition().getFault() != null
					|| event.getResponseDefinition().getStatus() < 300;
			if (!write || !applied) {
				continue;
			}
			Matcher matcher = MARKER.matcher(event.getRequest().getBodyAsString());
			while (matcher.find()) {
				writes.merge(matcher.group(1), 1, Integer::sum);
			}
		}
		return writes.values().stream().filter(count -> count > 1).count();
	}

	private static void report(String name, int attempts, int unsynced, long doubleWrites) {
		assertThat(doubleWrites).isZero();
		REPORT.put(name, attempts + " | " + unsynced + " | " + doubleWrites);
	}

	private LedgerSync awaitAttempts(long paymentId, int attempts) {
		await().atMost(Duration.ofSeconds(15)).until(() -> syncs.findByPaymentId(paymentId)
			.filter(s -> s.getAttempts() >= attempts && s.getStatus() != LedgerSyncStatus.IN_PROGRESS)
			.isPresent());
		return sync(paymentId);
	}

	private void awaitStatus(long paymentId, LedgerSyncStatus status) {
		await().atMost(Duration.ofSeconds(15))
			.until(() -> syncs.findByPaymentId(paymentId).map(LedgerSync::getStatus).orElse(null) == status);
	}

	private LedgerSync sync(long paymentId) {
		return syncs.findByPaymentId(paymentId).orElseThrow();
	}

	private PaymentStatus paymentStatus(long paymentId) {
		return PaymentStatus.valueOf(
				jdbc.queryForObject("SELECT status FROM billing_payment WHERE id = ?", String.class, paymentId));
	}

	private long pay(String className) {
		long cls = classes.create(DONGTAN, className, Money.of(280_000), Money.ZERO).getId();
		return payments.record(invoice(cls), PaymentMethod.CASH, Money.of(280_000), Money.ZERO, ADMIN).paymentId();
	}

	private long invoice(long cls) {
		long student = students.register(new StudentRegistration(DONGTAN, null, uniqueName(), "동탄고", Grade.HIGH_1,
				null, null, List.of(mother(randomPhone())))).id();
		return invoices.issue(new InvoiceDraft(student, YearMonth.of(2026, 11),
				List.of(new InvoiceDraft.LineDraft(cls, 8)), List.of()), ADMIN).id();
	}

}
