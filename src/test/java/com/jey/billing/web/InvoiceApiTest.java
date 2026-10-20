package com.jey.billing.web;

import static com.jey.TestCsrf.csrfToken;
import static com.jey.TestIdempotency.idempotencyKey;
import static com.jey.core.member.MemberFixtures.mother;
import static com.jey.core.member.MemberFixtures.randomPhone;
import static com.jey.core.member.MemberFixtures.registration;
import static com.jey.core.member.MemberFixtures.uniqueName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.MvcResult;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.campus.ClassService;
import com.jey.core.member.MemberFixtures;
import com.jey.core.member.StudentService;
import com.jey.core.shared.Money;

// 데스크의 수강료 청구: 미리 계산, 만들기, 단건, 그 달 목록. 청구는 학생의 지점에 속한다(다른 지점 직원은 403).
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InvoiceApiTest {

	private static final String QUOTE = "/api/v1/billing/quote";

	private static final String INVOICES = "/api/v1/billing/invoices";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	StudentService students;

	@Autowired
	ClassService classes;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = MemberFixtures.DONGTAN)
	void 미리_계산은_금액을_주고_저장하지_않는다() throws Exception {
		Fixture fixture = fixture();

		mockMvc.perform(post(QUOTE).with(csrfToken(mockMvc)).contentType(MediaType.APPLICATION_JSON)
				.content(body(fixture.student(), "2026-11", fixture.cls(), 9, "\"SIBLING_5\"")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").doesNotExist())
			.andExpect(jsonPath("$.status").doesNotExist())
			.andExpect(jsonPath("$.targetMonth").value("2026-11"))
			.andExpect(jsonPath("$.discountPolicy").value("ADDITIVE"))
			.andExpect(jsonPath("$.totalAmount").value(299250))
			.andExpect(jsonPath("$.lines[0].className").value(fixture.className()))
			.andExpect(jsonPath("$.lines[0].lessonCount").value(9))
			.andExpect(jsonPath("$.lines[0].listAmount").value(315000))
			.andExpect(jsonPath("$.lines[0].chargeAmount").value(299250))
			.andExpect(jsonPath("$.lines[0].discounts[0].code").value("SIBLING_5"))
			.andExpect(jsonPath("$.lines[0].discounts[0].rate").value(0.05))
			.andExpect(jsonPath("$.lines[0].discounts[0].amount").value(15750))
			// 8회 기준 수강료는 싣지 않는다(수강료표는 관리자 전용).
			.andExpect(jsonPath("$.lines[0].baseFee8").doesNotExist());
		assertThat(invoiceCount(fixture.student())).isZero();
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = MemberFixtures.DONGTAN)
	void 청구를_만들면_201이고_같은_키로_다시_보내면_처음의_응답이다() throws Exception {
		Fixture fixture = fixture();
		String key = UUID.randomUUID().toString();
		String body = body(fixture.student(), "2026-11", fixture.cls(), 8, "");

		String first = mockMvc.perform(post(INVOICES).with(csrfToken(mockMvc)).with(idempotencyKey(key))
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("ISSUED"))
			.andExpect(jsonPath("$.totalAmount").value(280000))
			.andReturn().getResponse().getContentAsString();
		String again = mockMvc.perform(post(INVOICES).with(csrfToken(mockMvc)).with(idempotencyKey(key))
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();

		assertThat(again).isEqualTo(first);
		assertThat(invoiceCount(fixture.student())).isOne();
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = MemberFixtures.DONGTAN)
	void 같은_달_두_번째는_409다() throws Exception {
		Fixture fixture = fixture();
		String body = body(fixture.student(), "2026-11", fixture.cls(), 8, "");
		issue(body).andExpect(status().isCreated());

		issue(body).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("BILLING_INVOICE_ALREADY_EXISTS"));
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 단건은_관리자가_보고_없는_청구는_404다() throws Exception {
		Fixture fixture = fixture();
		long id = idOf(issue(body(fixture.student(), "2026-11", fixture.cls(), 8, "")).andReturn());

		mockMvc.perform(get(INVOICES + "/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
		mockMvc.perform(get(INVOICES + "/" + Long.MAX_VALUE)).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("BILLING_INVOICE_NOT_FOUND"));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = MemberFixtures.NEUNGDONG)
	void 다른_지점_직원은_그_학생의_청구를_만들지도_보지도_못한다() throws Exception {
		Fixture fixture = fixture();

		issue(body(fixture.student(), "2026-11", fixture.cls(), 8, "")).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AUTH_CAMPUS_NOT_ALLOWED"));
		long id = idOfIssuedByAdmin(fixture);
		mockMvc.perform(get(INVOICES + "/" + id)).andExpect(status().isForbidden());
		mockMvc.perform(get(INVOICES).param("targetMonth", "2026-11").param("campusId", "1"))
			.andExpect(status().isForbidden());
		assertThat(invoiceCount(fixture.student())).isOne();
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = MemberFixtures.NEUNGDONG)
	void 목록의_직원은_소속_지점만_본다() throws Exception {
		Fixture dongtan = fixture();
		long dongtanInvoice = idOfIssuedByAdmin(dongtan);

		mockMvc.perform(get(INVOICES).param("targetMonth", "2026-11"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].id", not(hasItem((int) dongtanInvoice))))
			.andExpect(jsonPath("$.items[*].campusId", not(hasItem(1))));
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 목록은_그_달의_청구이고_대상_월이_필수다() throws Exception {
		Fixture fixture = fixture();
		long id = idOf(issue(body(fixture.student(), "2031-03", fixture.cls(), 8, "")).andReturn());

		mockMvc.perform(get(INVOICES).param("targetMonth", "2031-03").param("size", "100"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].id", hasItem((int) id)));
		mockMvc.perform(get(INVOICES)).andExpect(status().isBadRequest());
		mockMvc.perform(get(INVOICES).param("targetMonth", "2031-03").param("size", "101"))
			.andExpect(status().isBadRequest());
	}

	@ParameterizedTest
	@ValueSource(strings = { "\"2026-1\"", "\" 2026-11\"", "202611", "\"2026-11-01\"", "\"\"" })
	@WithJeyUser(role = UserRole.STAFF, campusId = MemberFixtures.DONGTAN)
	void 대상_월은_YYYY_MM_글자만_받는다(String month) throws Exception {
		String body = """
				{"studentId": 1, "targetMonth": %s, "lines": [{"classId": 1, "lessonCount": 8}]}
				""".formatted(month);

		mockMvc.perform(post(QUOTE).with(csrfToken(mockMvc)).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
	}

	@ParameterizedTest
	@ValueSource(strings = {
			// 반이 없다
			"{\"studentId\": 1, \"targetMonth\": \"2026-11\", \"lines\": []}",
			// 횟수가 범위 밖, 문자열
			"{\"studentId\": 1, \"targetMonth\": \"2026-11\", \"lines\": [{\"classId\": 1, \"lessonCount\": 32}]}",
			"{\"studentId\": 1, \"targetMonth\": \"2026-11\", \"lines\": [{\"classId\": 1, \"lessonCount\": \"9\"}]}",
			// 대상 월이 범위 밖
			"{\"studentId\": 1, \"targetMonth\": \"2100-01\", \"lines\": [{\"classId\": 1, \"lessonCount\": 8}]}",
			// 같은 반 두 번, 같은 할인 두 번
			"{\"studentId\": 1, \"targetMonth\": \"2026-11\", \"lines\": [{\"classId\": 1, \"lessonCount\": 4}, {\"classId\": 1, \"lessonCount\": 4}]}",
			"{\"studentId\": 1, \"targetMonth\": \"2026-11\", \"lines\": [{\"classId\": 1, \"lessonCount\": 8}], \"discountCodes\": [\"SIBLING_5\", \"SIBLING_5\"]}",
			// 모르는 칸
			"{\"studentId\": 1, \"targetMonth\": \"2026-11\", \"lines\": [{\"classId\": 1, \"lessonCount\": 8}], \"discount\": \"SIBLING_5\"}" })
	@WithJeyUser(role = UserRole.STAFF, campusId = MemberFixtures.DONGTAN)
	void 규칙_밖의_본문은_400이다(String body) throws Exception {
		mockMvc.perform(post(QUOTE).with(csrfToken(mockMvc)).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isBadRequest());
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = 1)
	void 연계_학원_계정은_쓰지_못한다() throws Exception {
		mockMvc.perform(get(INVOICES).param("targetMonth", "2026-11")).andExpect(status().isForbidden());
	}

	private org.springframework.test.web.servlet.ResultActions issue(String body) throws Exception {
		return mockMvc.perform(post(INVOICES).with(csrfToken(mockMvc)).with(idempotencyKey())
			.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private long idOfIssuedByAdmin(Fixture fixture) {
		return jdbc.queryForObject("SELECT id FROM billing_invoice WHERE student_id = ?", Long.class,
				insertInvoiceAsAdmin(fixture));
	}

	// 다른 지점 직원으로 로그인한 테스트에서 동탄관 청구를 하나 만들어 둔다(화면을 거치지 않고).
	private long insertInvoiceAsAdmin(Fixture fixture) {
		jdbc.update("INSERT INTO billing_invoice (campus_id, student_id, target_month, status, open_key, "
				+ "discount_policy, total_amount, created_at, updated_at) "
				+ "VALUES (1, ?, '2026-11', 'ISSUED', 1, 'ADDITIVE', 280000, NOW(6), NOW(6))", fixture.student());
		long id = jdbc.queryForObject("SELECT id FROM billing_invoice WHERE student_id = ?", Long.class,
				fixture.student());
		jdbc.update("INSERT INTO billing_invoice_line (invoice_id, class_id, lesson_count, base_fee_8, list_amount, "
				+ "charge_amount, sort_order, created_at, updated_at) VALUES (?, ?, 8, 280000, 280000, 280000, 0, "
				+ "NOW(6), NOW(6))", id, fixture.cls());
		return fixture.student();
	}

	private Fixture fixture() {
		long student = students.register(registration(uniqueName(), mother(randomPhone()))).id();
		String className = "반-" + UUID.randomUUID().toString().substring(0, 8);
		long cls = classes.create(MemberFixtures.DONGTAN, className, Money.of(280_000), Money.ZERO).getId();
		return new Fixture(student, cls, className);
	}

	private long invoiceCount(long student) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM billing_invoice WHERE student_id = ?", Long.class, student);
	}

	private static long idOf(MvcResult result) throws Exception {
		String json = result.getResponse().getContentAsString();
		return Long.parseLong(json.replaceAll("(?s).*\"id\":(\\d+).*", "$1"));
	}

	private static String body(long student, String month, long cls, int lessons, String discounts) {
		return """
				{"studentId": %d, "targetMonth": "%s", "lines": [{"classId": %d, "lessonCount": %d}], "discountCodes": [%s]}
				""".formatted(student, month, cls, lessons, discounts);
	}

	private record Fixture(long student, long cls, String className) {
	}

}
