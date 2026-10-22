package com.jey.billing.application;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.jey.core.member.MemberFixtures.DONGTAN;
import static com.jey.core.member.MemberFixtures.NEUNGDONG;
import static com.jey.core.member.MemberFixtures.mother;
import static com.jey.core.member.MemberFixtures.randomPhone;
import static com.jey.core.member.MemberFixtures.uniqueName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.github.tomakehurst.wiremock.http.Fault;
import com.jey.WithJeyUser;
import com.jey.billing.LedgerSheetsTestSupport;
import com.jey.billing.api.PaymentMethod;
import com.jey.billing.domain.LedgerSync;
import com.jey.billing.domain.LedgerSyncReason;
import com.jey.billing.domain.LedgerSyncRepository;
import com.jey.billing.domain.LedgerSyncStatus;
import com.jey.billing.domain.ledger.LedgerFormats;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.campus.ClassService;
import com.jey.core.member.StudentRegistration;
import com.jey.core.member.StudentService;
import com.jey.core.member.domain.Grade;
import com.jey.core.shared.Money;
import com.jey.core.shared.PhoneNumber;

// 결제를 기록하면 리스너가 그 지점 장부의 반 블록 빈 줄에 쓴다. 구글은 흉내 낸 서버다.
@WithJeyUser(id = LedgerSyncServiceTest.ADMIN_ID, role = UserRole.ADMIN)
class LedgerSyncServiceTest extends LedgerSheetsTestSupport {

	static final long ADMIN_ID = 900_896L;

	private static final AuthenticatedUser ADMIN = new AuthenticatedUser(ADMIN_ID, "admin", "관리자", UserRole.ADMIN,
			null, null);

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
	LedgerSyncService ledger;

	@Autowired
	LedgerSyncRepository syncs;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Clock clock;

	@Test
	void 결제하면_반_블록의_첫_빈_줄에_칸마다_쓰고_반영됨이다() {
		String className = newClassName();
		long cls = classes.create(DONGTAN, className, Money.of(280_000), Money.ZERO).getId();
		PhoneNumber phone = randomPhone();
		String name = uniqueName();
		long student = students.register(registration(DONGTAN, name, phone)).id();
		stubTab(className);
		stubWriteOk();

		PaymentView paid = pay(invoice(student, List.of(cls), 9), Money.of(315_000), Money.of(29_000));

		LedgerSync sync = awaitStatus(paid.paymentId(), LedgerSyncStatus.SYNCED);
		assertThat(sync.getSheetTab()).isEqualTo(TAB);
		assertThat(sync.getSheetRow()).isEqualTo(7);
		String date = LedgerFormats.date(LocalDate.ofInstant(paid.paidAt(), clock.getZone()));
		google.verify(1, postRequestedFor(urlPathEqualTo(WRITE_PATH))
			.withHeader("Authorization", matching("Bearer [\\w-]+\\.[\\w-]+\\.[\\w-]+"))
			.withRequestBody(equalToJson("""
					{"valueInputOption": "RAW", "data": [
					  {"range": "'11월 동탄관(26)'!C7", "values": [["동탄고1"]]},
					  {"range": "'11월 동탄관(26)'!D7", "values": [["%s(%s)"]]},
					  {"range": "'11월 동탄관(26)'!E7", "values": [["%s"]]},
					  {"range": "'11월 동탄관(26)'!F7", "values": [[315000]]},
					  {"range": "'11월 동탄관(26)'!G7", "values": [["11월 9회분 수강료"]]},
					  {"range": "'11월 동탄관(26)'!H7", "values": [[280000]]},
					  {"range": "'11월 동탄관(26)'!L7", "values": [["%s"]]},
					  {"range": "'11월 동탄관(26)'!M7", "values": [[315000]]},
					  {"range": "'11월 동탄관(26)'!N7", "values": [[29000]]},
					  {"range": "'11월 동탄관(26)'!O7", "values": [["11월 9회분 수강료 + 교재비 (29,000원)"]]},
					  {"range": "'11월 동탄관(26)'!P7", "values": [["앱:결제-%d"]]}
					]}""".formatted(name, className, LedgerFormats.phone(phone), date, paid.paymentId()))));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM core_audit_log WHERE action = 'billing.ledger-written' "
				+ "AND target_id = ? AND campus_id = ?", Long.class, student, DONGTAN)).isOne();
		// 리스너는 트랜잭션 없이 돌지만, 끝나면 이벤트 발행 기록은 완료로 닫힌다(다시 보내지 않는다).
		await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject("SELECT COUNT(*) FROM event_publication "
				+ "WHERE listener_id LIKE '%LedgerPaymentListener%' AND completion_date IS NULL "
				+ "AND serialized_event LIKE ?", Long.class, "%\"paymentId\":" + paid.paymentId() + ",%") == 0);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_publication WHERE listener_id LIKE "
				+ "'%LedgerPaymentListener%' AND serialized_event LIKE ?", Long.class,
				"%\"paymentId\":" + paid.paymentId() + ",%")).isOne();
	}

	@Test
	void 같은_결제를_다시_받아도_한_번만_쓴다() {
		String className = newClassName();
		long student = newStudent(DONGTAN);
		long cls = classes.create(DONGTAN, className, Money.of(280_000), Money.ZERO).getId();
		stubTab(className);
		stubWriteOk();
		PaymentView paid = pay(invoice(student, List.of(cls), 8), Money.of(280_000), Money.ZERO);
		awaitStatus(paid.paymentId(), LedgerSyncStatus.SYNCED);

		ledger.recordPayment(paid.paymentId());

		assertThat(syncs.findByPaymentId(paid.paymentId()).orElseThrow().getStatus()).isEqualTo(LedgerSyncStatus.SYNCED);
		google.verify(1, postRequestedFor(urlPathEqualTo(WRITE_PATH)));
	}

	@Test
	void 반_나눔_청구는_쓰지_않고_미반영이다() {
		long student = newStudent(DONGTAN);
		long first = classes.create(DONGTAN, newClassName(), Money.of(280_000), Money.ZERO).getId();
		long second = classes.create(DONGTAN, newClassName(), Money.of(280_000), Money.ZERO).getId();

		PaymentView paid = pay(invoice(student, List.of(first, second), 4), Money.of(280_000), Money.ZERO);

		assertThat(awaitStatus(paid.paymentId(), LedgerSyncStatus.UNSYNCED).getReason())
			.isEqualTo(LedgerSyncReason.SPLIT_CLASS);
		google.verify(0, getRequestedFor(urlPathEqualTo(READ_PATH)));
	}

	@Test
	void 장부를_정하지_않은_지점의_결제는_미반영이다() {
		long student = newStudent(NEUNGDONG);
		long cls = classes.create(NEUNGDONG, newClassName(), Money.of(280_000), Money.ZERO).getId();

		PaymentView paid = pay(invoice(student, List.of(cls), 8), Money.of(280_000), Money.ZERO);

		assertThat(awaitStatus(paid.paymentId(), LedgerSyncStatus.UNSYNCED).getReason())
			.isEqualTo(LedgerSyncReason.NOT_CONFIGURED);
		google.verify(0, postRequestedFor(urlPathEqualTo(WRITE_PATH))
			.withRequestBody(containing(LedgerFormats.marker(paid.paymentId()))));
	}

	@Test
	void 연락처가_없는_학생은_미반영이다() {
		long student = newStudent(DONGTAN);
		jdbc.update("DELETE FROM core_student_guardian WHERE student_id = ?", student);
		long cls = classes.create(DONGTAN, newClassName(), Money.of(280_000), Money.ZERO).getId();

		PaymentView paid = pay(invoice(student, List.of(cls), 8), Money.of(280_000), Money.ZERO);

		assertThat(awaitStatus(paid.paymentId(), LedgerSyncStatus.UNSYNCED).getReason())
			.isEqualTo(LedgerSyncReason.NO_CONTACT);
	}

	@Test
	void 대상_월의_탭이_없으면_미반영이다() {
		long student = newStudent(DONGTAN);
		long cls = classes.create(DONGTAN, newClassName(), Money.of(280_000), Money.ZERO).getId();
		google.stubFor(get(urlPathEqualTo(READ_PATH)).willReturn(aResponse().withStatus(400)
			.withHeader("Content-Type", "application/json")
			.withBody("{\"error\": {\"code\": 400, \"message\": \"Unable to parse range: '11월 동탄관(26)'!A:AZ\"}}")));

		PaymentView paid = pay(invoice(student, List.of(cls), 8), Money.of(280_000), Money.ZERO);

		assertThat(awaitStatus(paid.paymentId(), LedgerSyncStatus.UNSYNCED).getReason())
			.isEqualTo(LedgerSyncReason.TAB_NOT_FOUND);
	}

	@Test
	void 반_블록이_없으면_미반영이다() {
		long student = newStudent(DONGTAN);
		long cls = classes.create(DONGTAN, newClassName(), Money.of(280_000), Money.ZERO).getId();
		stubTab("다른반");

		PaymentView paid = pay(invoice(student, List.of(cls), 8), Money.of(280_000), Money.ZERO);

		assertThat(awaitStatus(paid.paymentId(), LedgerSyncStatus.UNSYNCED).getReason())
			.isEqualTo(LedgerSyncReason.BLOCK_NOT_FOUND);
	}

	@Test
	void 구글_장애면_다음_시도_시각을_두고_기다렸다가_스케줄러가_다시_쓴다() {
		String className = newClassName();
		long student = newStudent(DONGTAN);
		long cls = classes.create(DONGTAN, className, Money.of(280_000), Money.ZERO).getId();
		google.stubFor(get(urlPathEqualTo(READ_PATH)).willReturn(aResponse().withStatus(503)));

		PaymentView paid = pay(invoice(student, List.of(cls), 8), Money.of(280_000), Money.ZERO);

		await().atMost(Duration.ofSeconds(10)).until(() -> attemptsOf(paid.paymentId()) == 1
				&& syncs.findByPaymentId(paid.paymentId()).orElseThrow().getStatus() == LedgerSyncStatus.PENDING);
		LedgerSync waiting = syncs.findByPaymentId(paid.paymentId()).orElseThrow();
		assertThat(waiting.getNextAttemptAt()).isAfter(clock.instant().plusSeconds(30));

		google.resetAll();
		stubTab(className);
		stubWriteOk();
		jdbc.update("UPDATE billing_ledger_sync SET next_attempt_at = ? WHERE id = ?",
				java.sql.Timestamp.from(clock.instant().minusSeconds(1)), waiting.getId());
		ledger.retryDue();

		assertThat(syncs.findByPaymentId(paid.paymentId()).orElseThrow().getStatus()).isEqualTo(LedgerSyncStatus.SYNCED);
	}

	@Test
	void 쓴_뒤에_결제를_취소하면_그_줄_비고에_취소_표시를_남긴다() {
		String className = newClassName();
		long student = newStudent(DONGTAN);
		long cls = classes.create(DONGTAN, className, Money.of(280_000), Money.ZERO).getId();
		stubTab(className);
		stubWriteOk();
		PaymentView paid = pay(invoice(student, List.of(cls), 8), Money.of(280_000), Money.ZERO);
		awaitStatus(paid.paymentId(), LedgerSyncStatus.SYNCED);
		google.resetAll();
		stubRead(tab(className, List.of(filled("박박박", className),
				written("누누누", className, LedgerFormats.marker(paid.paymentId())), empty())));
		stubWriteOk();

		PaymentView canceled = payments.cancel(paid.paymentId(), ADMIN);

		LedgerSync sync = awaitStatus(paid.paymentId(), LedgerSyncStatus.CANCELED);
		assertThat(sync.isCancelMarked()).isTrue();
		String note = LedgerFormats.canceledNote(paid.paymentId(),
				LocalDate.ofInstant(canceled.canceledAt(), clock.getZone()));
		google.verify(1, postRequestedFor(urlPathEqualTo(WRITE_PATH)).withRequestBody(equalToJson("""
				{"valueInputOption": "RAW", "data": [{"range": "'11월 동탄관(26)'!P7", "values": [["%s"]]}]}""".formatted(note))));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM core_audit_log WHERE action = 'billing.ledger-cancel-marked' "
				+ "AND target_id = ?", Long.class, student)).isOne();
	}

	@Test
	void 쓰기_전에_결제를_취소하면_장부에_쓰지_않고_취소됨이다() {
		long student = newStudent(DONGTAN);
		long cls = classes.create(DONGTAN, newClassName(), Money.of(280_000), Money.ZERO).getId();
		stubTab("다른반");
		PaymentView paid = pay(invoice(student, List.of(cls), 8), Money.of(280_000), Money.ZERO);
		awaitStatus(paid.paymentId(), LedgerSyncStatus.UNSYNCED);

		payments.cancel(paid.paymentId(), ADMIN);

		LedgerSync sync = awaitStatus(paid.paymentId(), LedgerSyncStatus.CANCELED);
		assertThat(sync.isCancelMarked()).isFalse();
		google.verify(0, postRequestedFor(urlPathEqualTo(WRITE_PATH)));
	}

	@Test
	void 할인이_있으면_결제_내용에_할인_이름을_붙이고_매월_납입금액은_같은_할인의_8회분이다() {
		String className = newClassName();
		long student = newStudent(DONGTAN);
		long cls = classes.create(DONGTAN, className, Money.of(280_000), Money.ZERO).getId();
		stubTab(className);
		stubWriteOk();
		long invoiceId = invoices.issue(new InvoiceDraft(student, NOVEMBER, List.of(new InvoiceDraft.LineDraft(cls, 9)),
				List.of("SIBLING_5")), ADMIN).id();

		PaymentView paid = pay(invoiceId, Money.of(299_250), Money.ZERO);

		awaitStatus(paid.paymentId(), LedgerSyncStatus.SYNCED);
		google.verify(1, postRequestedFor(urlPathEqualTo(WRITE_PATH))
			.withRequestBody(equalToJson("""
					{"data": [{"range": "'11월 동탄관(26)'!F7", "values": [[299250]]}]}""", true, true))
			.withRequestBody(equalToJson("""
					{"data": [{"range": "'11월 동탄관(26)'!G7", "values": [["11월 9회분 수강료 (형제 할인 5%)"]]}]}""", true, true))
			.withRequestBody(equalToJson("""
					{"data": [{"range": "'11월 동탄관(26)'!H7", "values": [[266000]]}]}""", true, true)));
	}

	// 선생님이 비고 칸을 고쳐 표시가 사라졌다(앞에 메모를 붙임). 취소됨으로 끝내면 장부에 납부한 줄이 남는데 아무도 모른다.
	@Test
	void 쓴_뒤_표시가_사라진_결제를_취소하면_미반영으로_남긴다() {
		String className = newClassName();
		PaymentView paid = paidAndSynced(className);
		google.resetAll();
		stubRead(tab(className, List.of(filled("박박박", className),
				written("누누누", className, "현금 / " + LedgerFormats.marker(paid.paymentId())), empty())));
		stubWriteOk();

		payments.cancel(paid.paymentId(), ADMIN);

		assertThat(awaitStatus(paid.paymentId(), LedgerSyncStatus.UNSYNCED).getReason())
			.isEqualTo(LedgerSyncReason.MARKER_NOT_FOUND);
		google.verify(0, postRequestedFor(urlPathEqualTo(WRITE_PATH)));
	}

	@Test
	void 쓴_뒤_머리글이_깨진_탭에서_취소하면_미반영으로_남긴다() {
		String className = newClassName();
		PaymentView paid = paidAndSynced(className);
		google.resetAll();
		List<List<String>> broken = new ArrayList<>(tab(className, List.of(filled("박박박", className), empty())));
		broken.set(2, List.of("학년", "No", "학교", "이름"));
		stubRead(broken);

		payments.cancel(paid.paymentId(), ADMIN);

		assertThat(awaitStatus(paid.paymentId(), LedgerSyncStatus.UNSYNCED).getReason())
			.isEqualTo(LedgerSyncReason.HEADER_NOT_FOUND);
	}

	// 쓰기 요청이 닿았는데 응답을 잃었다(앱은 줄을 모른다). 취소는 표시를 찾아 취소 표시를 하고 그 줄을 남긴다.
	@Test
	void 쓰고_응답을_잃은_뒤_취소하면_표시를_찾아_취소_표시를_한다() {
		String className = newClassName();
		String scenario = "응답 잃은 뒤 취소";
		long student = newStudent(DONGTAN);
		long cls = classes.create(DONGTAN, className, Money.of(280_000), Money.ZERO).getId();
		google.stubFor(get(urlPathEqualTo(READ_PATH)).inScenario(scenario).whenScenarioStateIs(STARTED)
			.willReturn(okJson(json(tab(className, List.of(empty(), empty()))))));
		google.stubFor(post(urlPathEqualTo(WRITE_PATH)).inScenario(scenario).whenScenarioStateIs(STARTED)
			.willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)).willSetStateTo("written"));
		PaymentView paid = pay(invoice(student, List.of(cls), 8), Money.of(280_000), Money.ZERO);
		await().atMost(Duration.ofSeconds(15)).until(() -> attemptsOf(paid.paymentId()) == 1
				&& syncs.findByPaymentId(paid.paymentId()).orElseThrow().getStatus() == LedgerSyncStatus.PENDING);
		google.stubFor(get(urlPathEqualTo(READ_PATH)).inScenario(scenario).whenScenarioStateIs("written")
			.willReturn(okJson(json(tab(className,
					List.of(written("하하하", className, LedgerFormats.marker(paid.paymentId())), empty()))))));
		google.stubFor(post(urlPathEqualTo(WRITE_PATH)).inScenario(scenario).whenScenarioStateIs("written")
			.willReturn(okJson("{}")));
		expireLedgerLock();

		payments.cancel(paid.paymentId(), ADMIN);

		LedgerSync sync = awaitStatus(paid.paymentId(), LedgerSyncStatus.CANCELED);
		assertThat(sync.isCancelMarked()).isTrue();
		assertThat(sync.getSheetRow()).isEqualTo(6);
		// 처음 쓰기(응답을 잃은 것)도 P6에 표시를 썼다. 취소 표시는 한 번이다.
		google.verify(1, postRequestedFor(urlPathEqualTo(WRITE_PATH)).withRequestBody(containing("'11월 동탄관(26)'!P6"))
			.withRequestBody(containing("취소됨")));
	}

	// 쓰는 중에 취소가 왔다. 쓴 뒤 같은 리스너가 이어서 그 줄에 취소 표시를 한다(스케줄러를 기다리지 않는다).
	@Test
	void 쓰는_중에_취소가_오면_쓴_뒤_바로_취소_표시를_한다() {
		String className = newClassName();
		String scenario = "쓰는 중 취소";
		long student = newStudent(DONGTAN);
		long cls = classes.create(DONGTAN, className, Money.of(280_000), Money.ZERO).getId();
		google.stubFor(get(urlPathEqualTo(READ_PATH)).inScenario(scenario).whenScenarioStateIs(STARTED)
			.willReturn(okJson(json(tab(className, List.of(empty(), empty())))).withFixedDelay(700)));
		google.stubFor(post(urlPathEqualTo(WRITE_PATH)).inScenario(scenario).whenScenarioStateIs(STARTED)
			.willReturn(okJson("{}")).willSetStateTo("written"));
		PaymentView paid = pay(invoice(student, List.of(cls), 8), Money.of(280_000), Money.ZERO);
		google.stubFor(get(urlPathEqualTo(READ_PATH)).inScenario(scenario).whenScenarioStateIs("written")
			.willReturn(okJson(json(tab(className,
					List.of(written("하하하", className, LedgerFormats.marker(paid.paymentId())), empty()))))));
		google.stubFor(post(urlPathEqualTo(WRITE_PATH)).inScenario(scenario).whenScenarioStateIs("written")
			.willReturn(okJson("{}")));
		await().atMost(Duration.ofSeconds(5)).until(() -> syncs.findByPaymentId(paid.paymentId())
			.map(LedgerSync::getStatus).orElse(null) == LedgerSyncStatus.IN_PROGRESS);

		payments.cancel(paid.paymentId(), ADMIN);

		LedgerSync sync = awaitStatus(paid.paymentId(), LedgerSyncStatus.CANCELED);
		assertThat(sync.isCancelMarked()).isTrue();
		google.verify(1, postRequestedFor(urlPathEqualTo(WRITE_PATH)).withRequestBody(containing("'11월 동탄관(26)'!D6")));
		google.verify(1, postRequestedFor(urlPathEqualTo(WRITE_PATH)).withRequestBody(containing("'11월 동탄관(26)'!P6"))
			.withRequestBody(containing("취소됨")));
	}

	// 쓰는 중에 취소가 왔는데 그 쓰기가 못 쓰고 끝났다(반 블록 없음). 취소된 결제를 관리자의 미반영 목록에 남기지 않는다.
	@Test
	void 쓰는_중에_취소가_왔는데_쓰지_못하고_끝나면_취소됨이다() {
		long student = newStudent(DONGTAN);
		long cls = classes.create(DONGTAN, newClassName(), Money.of(280_000), Money.ZERO).getId();
		google.stubFor(get(urlPathEqualTo(READ_PATH))
			.willReturn(okJson(json(tab("다른반", List.of(empty())))).withFixedDelay(700)));
		PaymentView paid = pay(invoice(student, List.of(cls), 8), Money.of(280_000), Money.ZERO);
		await().atMost(Duration.ofSeconds(5)).until(() -> syncs.findByPaymentId(paid.paymentId())
			.map(LedgerSync::getStatus).orElse(null) == LedgerSyncStatus.IN_PROGRESS);

		payments.cancel(paid.paymentId(), ADMIN);

		LedgerSync sync = awaitStatus(paid.paymentId(), LedgerSyncStatus.CANCELED);
		assertThat(sync.isCancelMarked()).isFalse();
		assertThat(sync.getReason()).isNull();
	}

	@Test
	void 다른_쪽이_장부_잠금을_쥐고_있으면_시도로_세지_않고_미룬다() {
		String className = newClassName();
		stubTab(className);
		stubWriteOk();
		redis.opsForValue().set(LOCK_KEY, "다른 서버", Duration.ofMinutes(1));
		try {
			long student = newStudent(DONGTAN);
			long cls = classes.create(DONGTAN, className, Money.of(280_000), Money.ZERO).getId();

			PaymentView paid = pay(invoice(student, List.of(cls), 8), Money.of(280_000), Money.ZERO);

			await().atMost(Duration.ofSeconds(15)).until(() -> syncs.findByPaymentId(paid.paymentId())
				.filter(s -> s.getStatus() == LedgerSyncStatus.PENDING && s.getLastAttemptAt() != null).isPresent());
			LedgerSync waiting = syncs.findByPaymentId(paid.paymentId()).orElseThrow();
			assertThat(waiting.getAttempts()).isZero();
			assertThat(waiting.getNextAttemptAt()).isAfter(clock.instant().plusSeconds(20));
			assertThat(google.getAllServeEvents()).isEmpty();
			assertThat(redis.opsForValue().get(LOCK_KEY)).isEqualTo("다른 서버");

			expireLedgerLock();
			jdbc.update("UPDATE billing_ledger_sync SET next_attempt_at = ? WHERE id = ?",
					java.sql.Timestamp.from(clock.instant().minusSeconds(1)), waiting.getId());
			ledger.retryDue();

			assertThat(syncs.findByPaymentId(paid.paymentId()).orElseThrow().getStatus())
				.isEqualTo(LedgerSyncStatus.SYNCED);
		} finally {
			expireLedgerLock();
		}
	}

	// 쓰기 요청이 시간 초과로 끝나면 구글이 늦게 반영할 수 있다. 잠금을 바로 풀면 다음 결제가 같은 빈 줄에 써서 늦게 들어온 쓰기에 덮인다.
	@Test
	void 쓰기의_결과를_모르게_되면_장부_잠금을_시간이_다할_때까지_남긴다() {
		String className = newClassName();
		long student = newStudent(DONGTAN);
		long cls = classes.create(DONGTAN, className, Money.of(280_000), Money.ZERO).getId();
		stubTab(className);
		google.stubFor(post(urlPathEqualTo(WRITE_PATH)).willReturn(aResponse().withStatus(503)));

		PaymentView paid = pay(invoice(student, List.of(cls), 8), Money.of(280_000), Money.ZERO);

		await().atMost(Duration.ofSeconds(15)).until(() -> attemptsOf(paid.paymentId()) == 1
				&& syncs.findByPaymentId(paid.paymentId()).orElseThrow().getStatus() == LedgerSyncStatus.PENDING);
		assertThat(redis.hasKey(LOCK_KEY)).isTrue();
		assertThat(redis.getExpire(LOCK_KEY)).isPositive();
	}

	@Test
	void 읽기에서_실패하면_장부_잠금을_바로_푼다() {
		long student = newStudent(DONGTAN);
		long cls = classes.create(DONGTAN, newClassName(), Money.of(280_000), Money.ZERO).getId();
		google.stubFor(get(urlPathEqualTo(READ_PATH)).willReturn(aResponse().withStatus(503)));

		PaymentView paid = pay(invoice(student, List.of(cls), 8), Money.of(280_000), Money.ZERO);

		await().atMost(Duration.ofSeconds(15)).until(() -> attemptsOf(paid.paymentId()) == 1
				&& syncs.findByPaymentId(paid.paymentId()).orElseThrow().getStatus() == LedgerSyncStatus.PENDING);
		assertThat(redis.hasKey(LOCK_KEY)).isFalse();
	}

	private PaymentView paidAndSynced(String className) {
		long student = newStudent(DONGTAN);
		long cls = classes.create(DONGTAN, className, Money.of(280_000), Money.ZERO).getId();
		stubTab(className);
		stubWriteOk();
		PaymentView paid = pay(invoice(student, List.of(cls), 8), Money.of(280_000), Money.ZERO);
		awaitStatus(paid.paymentId(), LedgerSyncStatus.SYNCED);
		return paid;
	}

	private LedgerSync awaitStatus(long paymentId, LedgerSyncStatus status) {
		await().atMost(Duration.ofSeconds(15))
			.until(() -> syncs.findByPaymentId(paymentId).map(LedgerSync::getStatus).orElse(null) == status);
		return syncs.findByPaymentId(paymentId).orElseThrow();
	}

	private int attemptsOf(long paymentId) {
		return syncs.findByPaymentId(paymentId).map(LedgerSync::getAttempts).orElse(0);
	}

	private PaymentView pay(long invoiceId, Money amount, Money bookFee) {
		return payments.record(invoiceId, PaymentMethod.CASH, amount, bookFee, ADMIN);
	}

	private long invoice(long student, List<Long> classIds, int lessonsEach) {
		List<InvoiceDraft.LineDraft> lines = classIds.stream().map(id -> new InvoiceDraft.LineDraft(id, lessonsEach))
			.toList();
		return invoices.issue(new InvoiceDraft(student, NOVEMBER, lines, List.of()), ADMIN).id();
	}

	private long newStudent(long campus) {
		return students.register(registration(campus, uniqueName(), randomPhone())).id();
	}

	private static StudentRegistration registration(long campus, String name, PhoneNumber phone) {
		return new StudentRegistration(campus, null, name, "동탄고", Grade.HIGH_1, null, null, List.of(mother(phone)));
	}

}
