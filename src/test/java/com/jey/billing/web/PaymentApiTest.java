package com.jey.billing.web;

import static com.jey.TestCsrf.csrfToken;
import static com.jey.TestIdempotency.idempotencyKey;
import static com.jey.core.member.MemberFixtures.mother;
import static com.jey.core.member.MemberFixtures.randomPhone;
import static com.jey.core.member.MemberFixtures.registration;
import static com.jey.core.member.MemberFixtures.uniqueName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.billing.application.InvoiceDraft;
import com.jey.billing.application.InvoiceService;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.campus.ClassService;
import com.jey.core.member.MemberFixtures;
import com.jey.core.member.StudentService;
import com.jey.core.shared.Money;

// 데스크의 결제 기록·결제 취소·청구 취소·결제 목록. 결제와 청구는 학생의 지점에 속한다(다른 지점 직원은 403).
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentApiTest {

	private static final AuthenticatedUser ADMIN = new AuthenticatedUser(900_901L, "admin", "관리자", UserRole.ADMIN,
			null, null);

	@Autowired
	MockMvc mockMvc;

	@Autowired
	InvoiceService invoices;

	@Autowired
	StudentService students;

	@Autowired
	ClassService classes;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = MemberFixtures.DONGTAN)
	void 결제를_기록하면_201이고_같은_키로_다시_보내면_처음의_응답이다() throws Exception {
		long invoice = newInvoice();
		String key = UUID.randomUUID().toString();

		String first = pay(invoice, "{\"method\": \"CARD_POS\", \"amount\": 280000, \"bookFee\": 15000}", key)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.invoiceId").value(invoice))
			.andExpect(jsonPath("$.method").value("CARD_POS"))
			.andExpect(jsonPath("$.amount").value(280000))
			.andExpect(jsonPath("$.bookFee").value(15000))
			.andExpect(jsonPath("$.status").value("COMPLETED"))
			.andExpect(jsonPath("$.canceledAt").value((Object) null))
			.andReturn().getResponse().getContentAsString();
		String again = pay(invoice, "{\"method\": \"CARD_POS\", \"amount\": 280000, \"bookFee\": 15000}", key)
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();

		assertThat(again).isEqualTo(first);
		assertThat(paymentCount(invoice)).isOne();
		mockMvc.perform(get("/api/v1/billing/invoices/" + invoice))
			.andExpect(jsonPath("$.status").value("PAID"))
			.andExpect(jsonPath("$.payment.bookFee").value(15000));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = MemberFixtures.DONGTAN)
	void 교재비를_보내지_않으면_0원이고_결제_전_청구의_payment는_null이다() throws Exception {
		long invoice = newInvoice();
		mockMvc.perform(get("/api/v1/billing/invoices/" + invoice)).andExpect(jsonPath("$.payment").value((Object) null));

		pay(invoice, "{\"method\": \"CASH\", \"amount\": 280000}", UUID.randomUUID().toString())
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.bookFee").value(0));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = MemberFixtures.DONGTAN)
	void 금액이_다르면_409이고_두_번째_결제도_409다() throws Exception {
		long invoice = newInvoice();

		pay(invoice, "{\"method\": \"CASH\", \"amount\": 270000}", UUID.randomUUID().toString())
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("BILLING_PAYMENT_AMOUNT_MISMATCH"));
		pay(invoice, "{\"method\": \"CASH\", \"amount\": 280000}", UUID.randomUUID().toString())
			.andExpect(status().isCreated());
		pay(invoice, "{\"method\": \"CASH\", \"amount\": 280000}", UUID.randomUUID().toString())
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("BILLING_INVOICE_NOT_PAYABLE"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "{\"method\": \"CASH\", \"amount\": \"280000\"}", "{\"method\": \"CASH\", \"amount\": -1}",
			"{\"method\": \"ONLINE\", \"amount\": 280000}", "{\"method\": 0, \"amount\": 280000}",
			"{\"amount\": 280000}", "{\"method\": \"CASH\"}",
			"{\"method\": \"CASH\", \"amount\": 280000, \"bookFee\": 10000001}",
			"{\"method\": \"CASH\", \"amount\": 280000, \"bookFee\": 0.5}",
			"{\"method\": \"CASH\", \"amount\": 280000, \"memo\": \"x\"}" })
	@WithJeyUser(role = UserRole.STAFF, campusId = MemberFixtures.DONGTAN)
	void 규칙_밖의_본문은_400이고_결제하지_않는다(String body) throws Exception {
		long invoice = newInvoice();

		pay(invoice, body, UUID.randomUUID().toString()).andExpect(status().isBadRequest());
		assertThat(paymentCount(invoice)).isZero();
	}

	// 교재비 규칙 밖은 코드로 칸을 알린다(프론트가 교재비 칸에 안내를 띄운다).
	@ParameterizedTest
	@ValueSource(strings = { "10000001", "0.5" })
	@WithJeyUser(role = UserRole.STAFF, campusId = MemberFixtures.DONGTAN)
	void 교재비가_규칙_밖이면_BILLING_INVALID_BOOK_FEE다(String bookFee) throws Exception {
		long invoice = newInvoice();

		pay(invoice, "{\"method\": \"CASH\", \"amount\": 280000, \"bookFee\": " + bookFee + "}",
				UUID.randomUUID().toString())
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("BILLING_INVALID_BOOK_FEE"));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = MemberFixtures.DONGTAN)
	void 결제를_취소하면_청구가_다시_결제_전이고_청구를_취소할_수_있다() throws Exception {
		long invoice = newInvoice();
		long payment = idOf(pay(invoice, "{\"method\": \"CASH\", \"amount\": 280000}", UUID.randomUUID().toString()),
				"paymentId");

		cancelInvoice(invoice).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("BILLING_INVOICE_NOT_CANCELABLE"));
		mockMvc.perform(post("/api/v1/billing/payments/" + payment + "/cancel").with(csrfToken(mockMvc))
				.with(idempotencyKey()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CANCELED"))
			.andExpect(jsonPath("$.canceledAt").isNotEmpty());
		cancelInvoice(invoice).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELED"));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = MemberFixtures.NEUNGDONG)
	void 다른_지점_직원은_결제_취소_목록을_다루지_못한다() throws Exception {
		long invoice = newInvoice();
		long payment = jdbc.queryForObject("SELECT id FROM billing_payment WHERE invoice_id = ?", Long.class,
				payAsAdmin(invoice));

		pay(invoice, "{\"method\": \"CASH\", \"amount\": 280000}", UUID.randomUUID().toString())
			.andExpect(status().isForbidden());
		mockMvc.perform(post("/api/v1/billing/payments/" + payment + "/cancel").with(csrfToken(mockMvc))
				.with(idempotencyKey()))
			.andExpect(status().isForbidden());
		cancelInvoice(invoice).andExpect(status().isForbidden());
		mockMvc.perform(get("/api/v1/billing/payments").param("from", today()).param("to", today())
				.param("campusId", "1"))
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/v1/billing/payments").param("from", today()).param("to", today()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].paymentId", not(hasItem((int) payment))));
		assertThat(jdbc.queryForObject("SELECT status FROM billing_payment WHERE id = ?", String.class, payment))
			.isEqualTo("COMPLETED");
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 결제_목록은_결제일로_자르고_기간을_확인한다() throws Exception {
		long invoice = newInvoice();
		long payment = idOf(pay(invoice, "{\"method\": \"CASH\", \"amount\": 280000}", UUID.randomUUID().toString()),
				"paymentId");

		mockMvc.perform(get("/api/v1/billing/payments").param("from", today()).param("to", today())
				.param("size", "100"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].paymentId", hasItem((int) payment)));
		mockMvc.perform(get("/api/v1/billing/payments").param("from", today()).param("to", "2000-01-01"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("BILLING_INVALID_PERIOD"));
		mockMvc.perform(get("/api/v1/billing/payments").param("from", "2026-01-01").param("to", "2027-01-02"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("BILLING_PERIOD_TOO_LONG"));
		mockMvc.perform(get("/api/v1/billing/payments").param("from", today())).andExpect(status().isBadRequest());
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = 1)
	void 연계_학원_계정은_쓰지_못한다() throws Exception {
		mockMvc.perform(get("/api/v1/billing/payments").param("from", today()).param("to", today()))
			.andExpect(status().isForbidden());
	}

	private ResultActions pay(long invoice, String body, String key) throws Exception {
		return mockMvc.perform(post("/api/v1/billing/invoices/" + invoice + "/payments").with(csrfToken(mockMvc))
			.with(idempotencyKey(key)).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions cancelInvoice(long invoice) throws Exception {
		return mockMvc.perform(post("/api/v1/billing/invoices/" + invoice + "/cancel").with(csrfToken(mockMvc))
			.with(idempotencyKey()));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = MemberFixtures.DONGTAN)
	void 결제와_청구의_응답에_학생_이름이_있다() throws Exception {
		String name = uniqueName();
		long invoice = newInvoice(name);

		long payment = idOf(pay(invoice, "{\"method\": \"CASH\", \"amount\": 280000}", UUID.randomUUID().toString())
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.studentName").value(name)), "paymentId");
		mockMvc.perform(get("/api/v1/billing/invoices/" + invoice))
			.andExpect(jsonPath("$.studentName").value(name))
			.andExpect(jsonPath("$.payment.studentName").value(name));
		mockMvc.perform(get("/api/v1/billing/payments").param("from", today()).param("to", today()).param("size", "100"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.paymentId == " + payment + ")].studentName", contains(name)));
		mockMvc.perform(post("/api/v1/billing/payments/" + payment + "/cancel").with(csrfToken(mockMvc))
				.with(idempotencyKey()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.studentName").value(name));
	}

	// 다른 지점 직원으로 로그인한 테스트에서 결제를 하나 넣어 둔다(화면을 거치지 않고).
	private long payAsAdmin(long invoice) {
		jdbc.update("UPDATE billing_invoice SET status = 'PAID' WHERE id = ?", invoice);
		jdbc.update("INSERT INTO billing_payment (invoice_id, campus_id, student_id, method, amount, book_fee, status, "
				+ "open_key, paid_at, created_at, updated_at) SELECT id, campus_id, student_id, 'CASH', total_amount, 0, "
				+ "'COMPLETED', 1, NOW(6), NOW(6), NOW(6) FROM billing_invoice WHERE id = ?", invoice);
		return invoice;
	}

	// 청구는 서비스로 만든다(관리자). 로그인한 계정과 무관하게 동탄관 학생의 청구다.
	private long newInvoice() {
		return newInvoice(uniqueName());
	}

	private long newInvoice(String studentName) {
		long student = students.register(registration(studentName, mother(randomPhone()))).id();
		long cls = classes.create(MemberFixtures.DONGTAN, "반-" + UUID.randomUUID().toString().substring(0, 8),
				Money.of(280_000), Money.ZERO).getId();
		return invoices.issue(new InvoiceDraft(student, YearMonth.of(2026, 11),
				List.of(new InvoiceDraft.LineDraft(cls, 8)), List.of()), ADMIN).id();
	}

	private long paymentCount(long invoice) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM billing_payment WHERE invoice_id = ?", Long.class, invoice);
	}

	private static String today() {
		return LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
	}

	private static long idOf(ResultActions result, String field) throws Exception {
		String json = result.andReturn().getResponse().getContentAsString();
		return Long.parseLong(json.replaceAll("(?s).*\"" + field + "\":(\\d+).*", "$1"));
	}

}
