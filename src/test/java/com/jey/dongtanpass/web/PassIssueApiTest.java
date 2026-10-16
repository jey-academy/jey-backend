package com.jey.dongtanpass.web;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.PhoneNumber;
import com.jey.core.shared.PhoneProtector;
import com.jey.dongtanpass.application.ConsentNotice;
import com.jey.dongtanpass.domain.LinkToken;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static com.jey.TestCsrf.csrfToken;
import static com.jey.TestIdempotency.idempotencyKey;
import static com.jey.core.member.MemberFixtures.randomPhone;
import static com.jey.dongtanpass.DongtanpassFixtures.upsertPartner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 연계 학원이 결제를 확인하고 패스를 발급한다(ADR-0001, ADR-0035).
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PassIssueApiTest {

	private static final String PASSES = "/api/v1/dongtanpass/passes";

	private static final long PARTNER = 900_301L;

	private static final long INACTIVE = 900_302L;

	// 연계 학원 계정의 ID. 감사 로그의 행위자로 남는다.
	private static final long ACCOUNT = 900_311L;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PhoneProtector phoneProtector;

	@Autowired
	Clock clock;

	@Autowired
	JsonMapper jsonMapper;

	@BeforeEach
	void setUp() {
		upsertPartner(jdbc, PARTNER, true);
		upsertPartner(jdbc, INACTIVE, false);
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, id = ACCOUNT, partnerId = PARTNER)
	void 발급하면_패스_번호와_링크_토큰을_주고_이름과_번호_색인과_결제를_저장한다() throws Exception {
		String phone = randomPhone().value();

		MvcResult result = issue(body("  김하윤 ", phone, today().toString(), "150000", "\"CARD\"", true, version()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.passNo").value(matchesPattern("DT-[A-HJ-NP-Z2-9]{6}")))
			.andExpect(jsonPath("$.linkToken").value(matchesPattern("[A-Za-z0-9_-]{43}")))
			.andExpect(jsonPath("$.expiresOn").value(today().plusMonths(1).toString()))
			.andExpect(jsonPath("$.issuedAt").isNotEmpty())
			.andReturn();
		JsonNode response = json(result);
		long id = response.get("id").asLong();

		Map<String, Object> row = jdbc.queryForMap("SELECT * FROM dongtanpass_pass WHERE id = ?", id);
		assertThat(row).containsEntry("pass_no", response.get("passNo").asString())
			.containsEntry("student_name", "김하윤")
			.containsEntry("guardian_phone_bidx", phoneProtector.fullIndex(PhoneNumber.of(phone)))
			.containsEntry("link_token_hash", LinkToken.hashOf(response.get("linkToken").asString()))
			.containsEntry("partner_id", PARTNER)
			.containsEntry("status", "ISSUED")
			.containsEntry("payment_method", "CARD");
		assertThat(row.get("paid_amount").toString()).isEqualTo("150000");
		// 번호 원문과 링크 토큰 원문은 어느 칸에도 없다
		assertThat(row.values()).map(String::valueOf)
			.noneMatch(value -> value.contains(phone) || value.contains(response.get("linkToken").asString()));
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, id = ACCOUNT, partnerId = PARTNER)
	void 발급하면_보여_준_고지_문구의_버전으로_동의를_남긴다() throws Exception {
		long id = idOf(issue(validBody()).andExpect(status().isCreated()).andReturn());

		assertThat(jdbc.queryForMap(
				"SELECT consent_type, notice_version, channel FROM dongtanpass_consent WHERE pass_id = ?", id))
			.containsEntry("consent_type", "THIRD_PARTY_PROVISION")
			.containsEntry("notice_version", version())
			.containsEntry("channel", "PARTNER_CONFIRMED");
	}

	// 발급은 이벤트로 감사 로그에 남는다. 이름과 번호는 이벤트에도 감사 로그에도 없다.
	@Test
	@WithJeyUser(role = UserRole.PARTNER, id = ACCOUNT, partnerId = PARTNER)
	void 발급은_감사_로그에_이벤트_이름으로_남고_이름과_번호는_없다() throws Exception {
		String phone = randomPhone().value();
		String name = "감사" + UUID.randomUUID().toString().substring(0, 6);
		long id = idOf(issue(body(name, phone, today().toString(), "150000", "\"CASH\"", true, version()))
			.andExpect(status().isCreated())
			.andReturn());

		String eventId = await().atMost(Duration.ofSeconds(10))
			.until(() -> jdbc.queryForList(
					"SELECT event_id FROM core_audit_log WHERE action = 'dongtanpass.pass-issued' AND target_type = 'pass' AND target_id = ?",
					String.class, id), rows -> !rows.isEmpty())
			.getFirst();
		assertThat(jdbc.queryForList(
					"SELECT * FROM core_audit_log WHERE action = 'dongtanpass.pass-issued' AND target_type = 'pass' AND target_id = ?",
					id))
			.singleElement()
			.satisfies(record -> {
				assertThat(record).containsEntry("actor_id", ACCOUNT).containsEntry("campus_id", null);
				assertThat(record.values()).map(String::valueOf)
					.noneMatch(value -> value.contains(phone) || value.contains(name));
			});
		assertThat(jdbc.queryForList("SELECT serialized_event FROM event_publication WHERE serialized_event LIKE ?",
				String.class, "%" + eventId + "%"))
			// 발행 기록은 그 이벤트를 받는 리스너마다 한 줄이다.
			.isNotEmpty()
			.allSatisfy(event -> assertThat(event).doesNotContain(phone).doesNotContain(name));
	}

	// 응답을 받지 못한 화면이 같은 키로 다시 보내도 패스는 하나다. 처음의 응답(같은 링크 토큰)이 다시 나간다.
	@Test
	@WithJeyUser(role = UserRole.PARTNER, id = ACCOUNT, partnerId = PARTNER)
	void 같은_멱등_키로_두_번_보내면_패스는_하나이고_처음의_응답이_다시_나간다() throws Exception {
		String key = UUID.randomUUID().toString();
		String body = validBody();
		int before = passCount();

		JsonNode first = json(issue(body, key).andExpect(status().isCreated()).andReturn());
		JsonNode second = json(issue(body, key).andExpect(status().isCreated())
			.andExpect(header().string("Idempotency-Replayed", "true"))
			.andReturn());

		assertThat(second).isEqualTo(first);
		assertThat(passCount()).isEqualTo(before + 1);
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, id = ACCOUNT, partnerId = PARTNER)
	void 동의를_확인하지_않았으면_발급하지_않는다() throws Exception {
		int before = passCount();

		issue(body("김하윤", randomPhone().value(), today().toString(), "150000", "\"CARD\"", false, version()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_CONSENT_NOT_CONFIRMED"));

		assertThat(passCount()).isEqualTo(before);
	}

	// 화면이 예전 문구를 보여 주고 받은 동의다. 화면은 문구를 다시 받아 보여 줘야 한다.
	@Test
	@WithJeyUser(role = UserRole.PARTNER, id = ACCOUNT, partnerId = PARTNER)
	void 고지_버전이_지금_버전과_다르면_발급하지_않는다() throws Exception {
		int before = passCount();

		issue(body("김하윤", randomPhone().value(), today().toString(), "150000", "\"CARD\"", true, "2000-01-v0"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_NOTICE_VERSION_MISMATCH"));

		assertThat(passCount()).isEqualTo(before);
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, id = ACCOUNT, partnerId = PARTNER)
	void 결제일이_오늘보다_뒤면_발급하지_않는다() throws Exception {
		issue(body("김하윤", randomPhone().value(), today().plusDays(1).toString(), "150000", "\"CARD\"", true,
				version()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PAID_ON_IN_FUTURE"));
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, id = ACCOUNT, partnerId = PARTNER)
	void 결제일이_지난_날이어도_만료일은_발급일에서_센다() throws Exception {
		long id = idOf(issue(body("김하윤", randomPhone().value(), today().minusDays(3).toString(), "150000",
				"\"CARD\"", true, version()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.expiresOn").value(today().plusMonths(1).toString()))
			.andReturn());

		assertThat(jdbc.queryForMap("SELECT paid_on, expires_on FROM dongtanpass_pass WHERE id = ?", id))
			.containsEntry("paid_on", java.sql.Date.valueOf(today().minusDays(3)))
			.containsEntry("expires_on", java.sql.Date.valueOf(today().plusMonths(1)));
	}

	@ParameterizedTest
	@ValueSource(strings = { "0", "1000.5" })
	@WithJeyUser(role = UserRole.PARTNER, id = ACCOUNT, partnerId = PARTNER)
	void 결제_금액이_0원이거나_1원_단위가_아니면_발급하지_않는다(String amount) throws Exception {
		issue(body("김하윤", randomPhone().value(), today().toString(), amount, "\"CARD\"", true, version()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_INVALID_PAID_AMOUNT"));
	}

	// 본문은 명세의 타입 그대로만 읽는다(ADR-0036). 금액은 JSON 숫자다.
	@Test
	@WithJeyUser(role = UserRole.PARTNER, id = ACCOUNT, partnerId = PARTNER)
	void 금액을_문자열로_보내면_그_칸의_오류다() throws Exception {
		issue(body("김하윤", randomPhone().value(), today().toString(), "\"150000\"", "\"CARD\"", true, version()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("paidAmount"));
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, id = ACCOUNT, partnerId = PARTNER)
	void 결제_수단이_목록에_없으면_그_칸의_오류다() throws Exception {
		issue(body("김하윤", randomPhone().value(), today().toString(), "150000", "\"BITCOIN\"", true, version()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("paymentMethod"));
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, id = ACCOUNT, partnerId = PARTNER)
	void 학부모_번호가_휴대전화가_아니면_그_칸의_오류다() throws Exception {
		issue(body("김하윤", "02-123-4567", today().toString(), "150000", "\"CARD\"", true, version()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("guardianPhone"));
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, id = ACCOUNT, partnerId = PARTNER)
	void 이름이_비었거나_50자를_넘으면_그_칸의_오류다() throws Exception {
		issue(body(" ", randomPhone().value(), today().toString(), "150000", "\"CARD\"", true, version()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("studentName"));
		issue(body("가".repeat(51), randomPhone().value(), today().toString(), "150000", "\"CARD\"", true, version()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("studentName"));
	}

	// 칸 이름의 오타가 조용히 빈 값으로 처리되지 않게 한다.
	@Test
	@WithJeyUser(role = UserRole.PARTNER, id = ACCOUNT, partnerId = PARTNER)
	void 모르는_칸이_있으면_발급하지_않는다() throws Exception {
		int before = passCount();

		issue(validBody().replace("\"studentName\"", "\"studentNmae\""))
			.andExpect(status().isBadRequest());

		assertThat(passCount()).isEqualTo(before);
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, id = ACCOUNT, partnerId = INACTIVE)
	void 꺼진_학원은_발급할_수_없다() throws Exception {
		issue(validBody()).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PARTNER_INACTIVE"));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1)
	void 직원은_발급할_수_없다() throws Exception {
		issue(validBody()).andExpect(status().isForbidden());
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 관리자도_발급할_수_없다() throws Exception {
		issue(validBody()).andExpect(status().isForbidden());
	}

	private ResultActions issue(String json) throws Exception {
		return issue(json, UUID.randomUUID().toString());
	}

	private ResultActions issue(String json, String key) throws Exception {
		return mockMvc.perform(post(PASSES).with(csrfToken(mockMvc))
			.with(idempotencyKey(key))
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}

	private String validBody() {
		return body("김하윤", randomPhone().value(), today().toString(), "150000", "\"CARD\"", true, version());
	}

	// 금액과 수단은 JSON 그대로 넣는다(문자열 금액, 틀린 수단을 보내 보려고)
	private static String body(String name, String phone, String paidOn, String amount, String method,
			boolean consent, String version) {
		return """
				{"studentName":"%s","guardianPhone":"%s","paidOn":"%s","paidAmount":%s,"paymentMethod":%s,
				 "consentConfirmed":%s,"noticeVersion":"%s"}
				""".formatted(name, phone, paidOn, amount, method, consent, version);
	}

	private LocalDate today() {
		return LocalDate.now(clock);
	}

	private static String version() {
		return ConsentNotice.CURRENT.version();
	}

	private int passCount() {
		return jdbc.queryForObject("SELECT COUNT(*) FROM dongtanpass_pass", Integer.class);
	}

	private JsonNode json(MvcResult result) throws Exception {
		return jsonMapper.readTree(result.getResponse().getContentAsString());
	}

	private long idOf(MvcResult result) throws Exception {
		return json(result).get("id").asLong();
	}

}
