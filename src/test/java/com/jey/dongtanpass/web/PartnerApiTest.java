package com.jey.dongtanpass.web;

import java.util.Map;
import java.util.UUID;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.auth.api.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import jakarta.servlet.http.Cookie;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static com.jey.TestCsrf.csrfToken;
import static com.jey.TestIdempotency.idempotencyKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 연계 학원은 관리자가 패스 모듈에서 만든다 (ADR-0034).
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PartnerApiTest {

	private static final String PARTNERS = "/api/v1/dongtanpass/partners";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	JsonMapper objectMapper;

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 관리자가_연계_학원을_만들면_켜진_학원이_되고_만든_사실이_기록된다() throws Exception {
		String name = uniqueName();

		JsonNode created = json(create(" " + name + " ").andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").isNumber())
			.andExpect(jsonPath("$.name").value(name))
			.andExpect(jsonPath("$.type").value("ACADEMY"))
			.andExpect(jsonPath("$.active").value(true))
			.andExpect(jsonPath("$.createdAt").isString()));

		long id = created.get("id").asLong();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM core_audit_log WHERE action = 'dongtanpass.partner-created' "
				+ "AND target_type = 'partner' AND target_id = ? AND campus_id IS NULL", Integer.class, id))
			.isEqualTo(1);
	}

	// DB는 이름의 대소문자를 가리지 않는다. 대소문자만 다른 이름도 같은 학원이다.
	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 이미_있는_이름으로는_만들_수_없다() throws Exception {
		String name = uniqueName();
		create(name).andExpect(status().isCreated());

		create(name).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PARTNER_NAME_DUPLICATED"));
		create(name.toUpperCase()).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PARTNER_NAME_DUPLICATED"));
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 비었거나_너무_긴_이름은_입력_오류다() throws Exception {
		create("  ").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
		create("가".repeat(51)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 목록은_만든_순서다() throws Exception {
		String first = uniqueName();
		String second = uniqueName();
		long firstId = json(create(first).andExpect(status().isCreated())).get("id").asLong();
		long secondId = json(create(second).andExpect(status().isCreated())).get("id").asLong();

		JsonNode list = json(mockMvc.perform(get(PARTNERS)).andExpect(status().isOk()));

		assertThat(list.isArray()).isTrue();
		int firstAt = indexOf(list, firstId);
		int secondAt = indexOf(list, secondId);
		assertThat(firstAt).isNotNegative();
		assertThat(secondAt).isGreaterThan(firstAt);
		assertThat(list.get(firstAt).get("name").asString()).isEqualTo(first);
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1)
	void 직원은_연계_학원을_보거나_만들_수_없다() throws Exception {
		mockMvc.perform(get(PARTNERS)).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AUTH_ROLE_NOT_ALLOWED"));
		create(uniqueName()).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AUTH_ROLE_NOT_ALLOWED"));
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = 1)
	void 연계_학원_계정은_연계_학원을_보거나_만들_수_없다() throws Exception {
		mockMvc.perform(get(PARTNERS)).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AUTH_ROLE_NOT_ALLOWED"));
		create(uniqueName()).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AUTH_ROLE_NOT_ALLOWED"));
	}

	// 연계 학원 계정은 관리자가 그 학원에서 만든다. 만든 계정으로 바로 로그인할 수 있다.
	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 관리자가_연계_학원의_계정을_만들면_그_학원_소속으로_로그인할_수_있다() throws Exception {
		long partnerId = newPartner();
		String loginId = newLoginId();

		JsonNode created = json(createAccount(partnerId, loginId, "햇살수학 데스크",
				"partner-password-123")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").isNumber())
			.andExpect(jsonPath("$.loginId").value(loginId))
			.andExpect(jsonPath("$.name").value("햇살수학 데스크"))
			.andExpect(jsonPath("$.partnerId").value(partnerId))
			.andExpect(jsonPath("$.createdAt").isString())
			.andExpect(jsonPath("$.password").doesNotExist()));

		assertThat(jdbc.queryForObject("SELECT partner_id FROM core_user WHERE id = ?", Long.class,
				created.get("id").asLong()))
			.isEqualTo(partnerId);
		// 여기부터는 실제 로그인으로 본다. 이 테스트의 관리자 로그인 정보가 남아 있으면 MockMvc가 모든 요청에 그것을 쓴다.
		TestSecurityContextHolder.clearContext();
		Cookie session = login(loginId, "partner-password-123");
		mockMvc.perform(get("/api/v1/auth/me").cookie(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.role").value("PARTNER"))
			.andExpect(jsonPath("$.campusId").value(nullValue()));
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 없는_학원이나_꺼진_학원에는_계정을_만들_수_없다() throws Exception {
		createAccount(Long.MAX_VALUE, newLoginId(), "없는학원", "partner-password-123")
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PARTNER_NOT_FOUND"));

		long partnerId = newPartner();
		jdbc.update("UPDATE dongtanpass_partner SET active = FALSE WHERE id = ?", partnerId);
		String loginId = newLoginId();
		createAccount(partnerId, loginId, "꺼진학원", "partner-password-123").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PARTNER_INACTIVE_FOR_ACCOUNT"));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM core_user WHERE login_id = ?", Integer.class, loginId))
			.isZero();
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 계정의_아이디와_비밀번호는_계정_관리와_같은_규칙을_따른다() throws Exception {
		long partnerId = newPartner();
		String loginId = newLoginId();
		createAccount(partnerId, loginId, "햇살수학", "partner-password-123").andExpect(status().isCreated());

		createAccount(partnerId, loginId, "햇살수학", "partner-password-456").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("AUTH_LOGIN_ID_DUPLICATED"));
		createAccount(partnerId, newLoginId(), "햇살수학", "short").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("AUTH_PASSWORD_NOT_ACCEPTABLE"));
		createAccount(partnerId, "한글아이디", "햇살수학", "partner-password-123").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
		createAccount(partnerId, newLoginId(), "가".repeat(51), "partner-password-123")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
	}

	@Test
	void 계정_만들기_요청을_로그에_찍어도_비밀번호는_나오지_않는다() {
		var request = new PartnerAccountCreateRequest("sunshine", "햇살수학", "partner-password-123");

		assertThat(request.toString()).contains("sunshine").doesNotContain("partner-password-123");
	}

	// 만든 뒤의 관리는 계정 관리 API가 한다. 소속(역할)만 바꿀 수 없다(ADR-0034).
	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 만든_연계_학원_계정은_계정_관리에서_고치고_차단하고_풀_수_있지만_역할은_바꿀_수_없다() throws Exception {
		long partnerId = newPartner();
		long accountId = json(createAccount(partnerId, newLoginId(), "햇살수학", "partner-password-123")
			.andExpect(status().isCreated())).get("id").asLong();
		String accounts = "/api/v1/accounts/" + accountId;

		send(put(accounts), Map.of("name", "햇살수학 본원", "role", "PARTNER")).andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("햇살수학 본원"))
			.andExpect(jsonPath("$.partnerId").value(partnerId));
		send(post(accounts + "/disable"), Map.of()).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DISABLED"));
		send(post(accounts + "/enable"), Map.of()).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACTIVE"));
		send(post(accounts + "/password"), Map.of("password", "partner-password-789"))
			.andExpect(status().isNoContent());
		send(put(accounts), Map.of("name", "햇살수학 본원", "role", "STAFF", "campusId", 1))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("AUTH_PARTNER_ACCOUNT_NOT_SUPPORTED"));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1)
	void 직원은_연계_학원의_계정을_만들_수_없다() throws Exception {
		createAccount(1, newLoginId(), "햇살수학", "partner-password-123").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AUTH_ROLE_NOT_ALLOWED"));
	}

	private long newPartner() throws Exception {
		return json(create(uniqueName()).andExpect(status().isCreated())).get("id").asLong();
	}

	private ResultActions createAccount(long partnerId, String loginId, String name, String password)
			throws Exception {
		return send(post(PARTNERS + "/{id}/accounts", partnerId),
				Map.of("loginId", loginId, "name", name, "password", password));
	}

	// 계정 API는 멱등 키를 받지 않는다(비밀번호가 실린다).
	private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
			Map<String, ?> body) throws Exception {
		return mockMvc.perform(request.with(csrfToken(mockMvc))
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}

	private Cookie login(String loginId, String password) throws Exception {
		Cookie session = mockMvc
			.perform(post("/api/v1/auth/login").with(csrfToken(mockMvc))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("loginId", loginId, "password", password))))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getCookie("SESSION");
		assertThat(session).as("로그인 응답의 세션 쿠키").isNotNull();
		return session;
	}

	private static String newLoginId() {
		return "partner-" + UUID.randomUUID().toString().substring(0, 8);
	}

	private ResultActions create(String name) throws Exception {
		return mockMvc.perform(post(PARTNERS).with(csrfToken(mockMvc))
			.with(idempotencyKey())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("name", name))));
	}

	private JsonNode json(ResultActions result) throws Exception {
		return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
	}

	private static int indexOf(JsonNode list, long id) {
		for (int i = 0; i < list.size(); i++) {
			if (list.get(i).get("id").asLong() == id) {
				return i;
			}
		}
		return -1;
	}

	private static String uniqueName() {
		return "학원-" + UUID.randomUUID().toString().substring(0, 8);
	}

}
