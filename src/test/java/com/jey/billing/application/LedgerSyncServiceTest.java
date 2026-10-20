package com.jey.billing.application;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
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
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.jey.TestcontainersConfiguration;
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
import tools.jackson.databind.json.JsonMapper;

// 결제를 기록하면 리스너가 그 지점 장부의 반 블록 빈 줄에 쓴다. 구글은 흉내 낸 서버다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
@WithJeyUser(id = LedgerSyncServiceTest.ADMIN_ID, role = UserRole.ADMIN)
class LedgerSyncServiceTest extends LedgerSheetsTestSupport {

	static final long ADMIN_ID = 900_896L;

	private static final AuthenticatedUser ADMIN = new AuthenticatedUser(ADMIN_ID, "admin", "관리자", UserRole.ADMIN,
			null, null);

	private static final YearMonth NOVEMBER = YearMonth.of(2026, 11);

	private static final String READ_PATH = "/v4/spreadsheets/" + SHEET_ID + "/values/'2026-11'!A:AZ";

	private static final String WRITE_PATH = "/v4/spreadsheets/" + SHEET_ID + "/values:batchUpdate";

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
		assertThat(sync.getSheetTab()).isEqualTo("2026-11");
		assertThat(sync.getSheetRow()).isEqualTo(7);
		String date = LedgerFormats.date(LocalDate.ofInstant(paid.paidAt(), clock.getZone()));
		google.verify(1, postRequestedFor(urlPathEqualTo(WRITE_PATH))
			.withHeader("Authorization", matching("Bearer [\\w-]+\\.[\\w-]+\\.[\\w-]+"))
			.withRequestBody(equalToJson("""
					{"valueInputOption": "RAW", "data": [
					  {"range": "'2026-11'!C7", "values": [["동탄고1"]]},
					  {"range": "'2026-11'!D7", "values": [["%s(%s)"]]},
					  {"range": "'2026-11'!E7", "values": [["%s"]]},
					  {"range": "'2026-11'!F7", "values": [[315000]]},
					  {"range": "'2026-11'!G7", "values": [["11월 9회분 수강료"]]},
					  {"range": "'2026-11'!H7", "values": [[280000]]},
					  {"range": "'2026-11'!L7", "values": [["%s"]]},
					  {"range": "'2026-11'!M7", "values": [[315000]]},
					  {"range": "'2026-11'!N7", "values": [[29000]]},
					  {"range": "'2026-11'!O7", "values": [["11월 9회분 수강료 + 교재비 (29,000원)"]]},
					  {"range": "'2026-11'!P7", "values": [["앱:결제-%d"]]}
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
		assertThat(google.getAllServeEvents()).isEmpty();
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
			.withBody("{\"error\": {\"code\": 400, \"message\": \"Unable to parse range: '2026-11'!A:AZ\"}}")));

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
		stubTab(className, List.of("", "2", "동탄초5", "박박박(" + className + ")"), List.of("", "3", "동탄고1",
				"누누누(" + className + ")", "", "", "", "", "", "", "", "", "", "", "", LedgerFormats.marker(paid.paymentId())));
		stubWriteOk();

		PaymentView canceled = payments.cancel(paid.paymentId(), ADMIN);

		LedgerSync sync = awaitStatus(paid.paymentId(), LedgerSyncStatus.CANCELED);
		assertThat(sync.isCancelMarked()).isTrue();
		String note = LedgerFormats.canceledNote(paid.paymentId(),
				LocalDate.ofInstant(canceled.canceledAt(), clock.getZone()));
		google.verify(1, postRequestedFor(urlPathEqualTo(WRITE_PATH)).withRequestBody(equalToJson("""
				{"valueInputOption": "RAW", "data": [{"range": "'2026-11'!P7", "values": [["%s"]]}]}""".formatted(note))));
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

	private static String newClassName() {
		return "L" + UUID.randomUUID().toString().substring(0, 6);
	}

	// 시안 모양의 11월 탭: 3행 머리글(P열 비고), 4행 보조 머리글, 5행부터 그 반 블록(5·6행은 사람이 쓴 줄, 7행부터 빈 줄 셋).
	@SafeVarargs
	private static void stubTab(String className, List<String>... extraRows) {
		List<List<String>> values = new ArrayList<>();
		values.add(List.of("11월 수강료 납부 일지 [동탄관]"));
		values.add(List.of());
		values.add(List.of("학년", "No", "학교", "성명", "부모 전화", "납입금액", "결제 내용", "매월 납입금액\n(8회기준)", "8월", "9월",
				"10월", "11월", "수강료", "교재비", "이번달 납부 내용", "비고"));
		values.add(List.of("", "", "", "", "", "", "", "", "납부일", "납부일", "납부일", "납부일"));
		values.add(List.of(className + "\nBH/SH", "1", "동탄초5", "김김김(" + className + ")", "(010) 0000-0101"));
		if (extraRows.length == 0) {
			values.add(List.of("", "2", "동탄초5", "박박박(" + className + ")"));
		} else {
			values.addAll(List.of(extraRows));
		}
		values.add(List.of("", "3"));
		values.add(List.of("", "4"));
		values.add(List.of("", "5"));
		values.add(List.of("Z9\nBH", "1"));
		google.stubFor(get(urlPathEqualTo(READ_PATH))
			.willReturn(okJson(JsonMapper.builder().build().writeValueAsString(Map.of("values", values)))));
	}

	private static void stubWriteOk() {
		google.stubFor(post(urlPathEqualTo(WRITE_PATH)).willReturn(okJson("{\"totalUpdatedCells\": 1}")));
	}

}
