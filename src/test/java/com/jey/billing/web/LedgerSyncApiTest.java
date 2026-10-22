package com.jey.billing.web;

import static com.jey.TestCsrf.csrfToken;
import static com.jey.TestIdempotency.idempotencyKey;
import static com.jey.core.member.MemberFixtures.DONGTAN;
import static com.jey.core.member.MemberFixtures.NEUNGDONG;
import static com.jey.core.member.MemberFixtures.mother;
import static com.jey.core.member.MemberFixtures.randomPhone;
import static com.jey.core.member.MemberFixtures.uniqueName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.YearMonth;
import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import com.jey.WithJeyUser;
import com.jey.billing.LedgerSheetsTestSupport;
import com.jey.billing.api.PaymentMethod;
import com.jey.billing.application.InvoiceDraft;
import com.jey.billing.application.InvoiceService;
import com.jey.billing.application.PaymentService;
import com.jey.billing.domain.LedgerSync;
import com.jey.billing.domain.LedgerSyncRepository;
import com.jey.billing.domain.LedgerSyncStatus;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.campus.ClassService;
import com.jey.core.member.StudentRegistration;
import com.jey.core.member.StudentService;
import com.jey.core.member.domain.Grade;
import com.jey.core.shared.Money;

// 장부 미반영 목록과 다시 반영. 관리자 전용이다. 응답에 이름·전화번호를 싣지 않는다.
class LedgerSyncApiTest extends LedgerSheetsTestSupport {

	private static final AuthenticatedUser ADMIN = new AuthenticatedUser(900_898L, "admin", "관리자", UserRole.ADMIN,
			null, null);

	private static final String UNSYNCED = "/api/v1/billing/ledger-sync/unsynced";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	PaymentService payments;

	@Autowired
	InvoiceService invoices;

	@Autowired
	StudentService students;

	@Autowired
	ClassService classes;

	@Autowired
	LedgerSyncRepository syncs;

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 관리자는_미반영_목록을_최근_것부터_보고_학생_이름은_있고_번호는_없다() throws Exception {
		stubTab("다른반");
		String studentName = uniqueName();
		LedgerSync sync = unsynced(DONGTAN, newClassName(), studentName);

		mockMvc.perform(get(UNSYNCED).param("campusId", String.valueOf(DONGTAN)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].id").value(sync.getId()))
			.andExpect(jsonPath("$.items[0].paymentId").value(sync.getPaymentId()))
			.andExpect(jsonPath("$.items[0].studentId").value(sync.getStudentId()))
			.andExpect(jsonPath("$.items[0].campusId").value(DONGTAN))
			.andExpect(jsonPath("$.items[0].targetMonth").value("2026-11"))
			.andExpect(jsonPath("$.items[0].reason").value("BLOCK_NOT_FOUND"))
			.andExpect(jsonPath("$.items[0].attempts").value(1))
			.andExpect(jsonPath("$.items[0].lastAttemptAt").isNotEmpty())
			.andExpect(jsonPath("$.items[0].studentName").value(studentName))
			.andExpect(jsonPath("$.items[0].phone").doesNotExist())
			.andExpect(jsonPath("$.items[*].campusId", Matchers.everyItem(Matchers.is((int) DONGTAN))))
			.andExpect(jsonPath("$.page").value(0))
			.andExpect(jsonPath("$.totalElements").isNumber());
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 지점을_고르지_않으면_전_지점이다() throws Exception {
		LedgerSync other = unsynced(NEUNGDONG, newClassName());

		mockMvc.perform(get(UNSYNCED).param("size", "100"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].id", Matchers.hasItem((int) (long) other.getId())));
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 장부를_고친_뒤_다시_반영하면_바로_써_보고_결과_상태를_준다() throws Exception {
		String className = newClassName();
		stubTab("다른반");
		LedgerSync sync = unsynced(DONGTAN, className);
		google.resetAll();
		stubTab(className);
		stubWriteOk();

		mockMvc.perform(post("/api/v1/billing/ledger-sync/" + sync.getId() + "/retry").with(csrfToken(mockMvc))
			.with(idempotencyKey()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(sync.getId()))
			.andExpect(jsonPath("$.status").value("SYNCED"))
			.andExpect(jsonPath("$.reason").doesNotExist());

		assertThat(syncs.findById(sync.getId()).orElseThrow().getSheetRow()).isEqualTo(7);
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 다시_해도_안_되면_미반영과_이유를_준다() throws Exception {
		stubTab("다른반");
		LedgerSync sync = unsynced(DONGTAN, newClassName());

		mockMvc.perform(post("/api/v1/billing/ledger-sync/" + sync.getId() + "/retry").with(csrfToken(mockMvc))
			.with(idempotencyKey()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UNSYNCED"))
			.andExpect(jsonPath("$.reason").value("BLOCK_NOT_FOUND"));
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 미반영이_아니면_409이고_없으면_404다() throws Exception {
		String className = newClassName();
		stubTab(className);
		stubWriteOk();
		long paymentId = pay(DONGTAN, className, uniqueName());
		await().atMost(Duration.ofSeconds(15)).until(() -> syncs.findByPaymentId(paymentId)
			.map(LedgerSync::getStatus).orElse(null) == LedgerSyncStatus.SYNCED);
		long id = syncs.findByPaymentId(paymentId).orElseThrow().getId();

		mockMvc.perform(post("/api/v1/billing/ledger-sync/" + id + "/retry").with(csrfToken(mockMvc))
			.with(idempotencyKey()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("BILLING_LEDGER_SYNC_NOT_RETRYABLE"));
		mockMvc.perform(post("/api/v1/billing/ledger-sync/999999999/retry").with(csrfToken(mockMvc))
			.with(idempotencyKey()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("BILLING_LEDGER_SYNC_NOT_FOUND"));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 직원은_목록도_다시_반영도_403이다() throws Exception {
		mockMvc.perform(get(UNSYNCED)).andExpect(status().isForbidden());
		mockMvc.perform(post("/api/v1/billing/ledger-sync/1/retry").with(csrfToken(mockMvc)).with(idempotencyKey()))
			.andExpect(status().isForbidden());
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 페이지_번호가_한도를_넘으면_400이다() throws Exception {
		mockMvc.perform(get(UNSYNCED).param("page", "1000000000")).andExpect(status().isBadRequest());
		mockMvc.perform(get(UNSYNCED).param("size", "101")).andExpect(status().isBadRequest());
	}

	// 결제를 하나 넣고 리스너가 미반영으로 끝낼 때까지 기다린다.
	private LedgerSync unsynced(long campus, String className) {
		return unsynced(campus, className, uniqueName());
	}

	private LedgerSync unsynced(long campus, String className, String studentName) {
		long paymentId = pay(campus, className, studentName);
		await().atMost(Duration.ofSeconds(15)).until(() -> syncs.findByPaymentId(paymentId)
			.map(LedgerSync::getStatus).orElse(null) == LedgerSyncStatus.UNSYNCED);
		return syncs.findByPaymentId(paymentId).orElseThrow();
	}

	private long pay(long campus, String className, String studentName) {
		long cls = classes.create(campus, className, Money.of(280_000), Money.ZERO).getId();
		long student = students.register(new StudentRegistration(campus, null, studentName, "동탄고", Grade.HIGH_1, null,
				null, List.of(mother(randomPhone())))).id();
		long invoice = invoices.issue(new InvoiceDraft(student, YearMonth.of(2026, 11),
				List.of(new InvoiceDraft.LineDraft(cls, 8)), List.of()), ADMIN).id();
		return payments.record(invoice, PaymentMethod.CASH, Money.of(280_000), Money.ZERO, ADMIN).paymentId();
	}

}
