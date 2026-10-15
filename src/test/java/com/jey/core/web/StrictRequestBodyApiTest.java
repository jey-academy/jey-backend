package com.jey.core.web;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static com.jey.TestCsrf.csrfToken;
import static com.jey.TestIdempotency.idempotencyKey;
import static com.jey.core.member.MemberFixtures.randomPhone;
import static com.jey.core.member.MemberFixtures.uniqueName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 실제 API에서 요청 본문을 명세의 타입 그대로만 읽는지 본다 (ADR-0036). 규칙의 갈래는 StrictRequestJsonTest와 GlobalExceptionHandlerTest가 본다.
// 여기 있는 요청은 모두 전에는 다른 뜻으로 처리되던 것이다(문자열 지점 ID, 번호로 된 학년·역할, 배열 날짜, 빈 금액은 서버 오류, 범위를 넘는 page는 칸 없는 오류).
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StrictRequestBodyApiTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1)
	void 학생_등록은_명세의_타입과_다른_값을_그_칸의_오류로_답한다() throws Exception {
		String phone = randomPhone().value();

		register(studentJson("\"1\"", "\"HIGH_1\"", "\"2026-10-15\"", phone)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("campusId"));
		register(studentJson("1", "1", "\"2026-10-15\"", phone)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("grade"));
		register(studentJson("1", "\"HIGH_1\"", "[2026,10,15]", phone)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("registeredAt"));

		// 같은 본문을 명세의 타입 그대로 보내면 등록된다.
		register(studentJson("1", "\"HIGH_1\"", "\"2026-10-15\"", phone)).andExpect(status().isCreated());
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1)
	void 학생_검색의_범위를_넘는_page는_그_칸의_오류다() throws Exception {
		send(post("/api/v1/students/search"), "{\"page\":99999999999}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("page"));
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 반의_빈_금액은_서버_오류가_아니라_그_칸의_오류다() throws Exception {
		send(post("/api/v1/classes").with(idempotencyKey()), """
				{"campusId":1,"name":"%s","baseFee8":"","bookFee":0}
				""".formatted("반-" + UUID.randomUUID().toString().substring(0, 8)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("baseFee8"));
	}

	// 번호로 된 역할을 받으면 0이 관리자 역할이 된다. 계정이 만들어지지 않아야 한다.
	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 계정의_역할을_번호로_보내면_그_칸의_오류이고_계정은_만들어지지_않는다() throws Exception {
		String loginId = "strict-" + UUID.randomUUID().toString().substring(0, 8);

		send(post("/api/v1/accounts"), """
				{"loginId":"%s","name":"번호역할","password":"strict-password-123","role":0,"campusId":null}
				""".formatted(loginId))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("role"));

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM core_user WHERE login_id = ?", Integer.class, loginId))
			.isZero();
	}

	private ResultActions register(String json) throws Exception {
		return send(post("/api/v1/students").with(idempotencyKey()), json);
	}

	private ResultActions send(MockHttpServletRequestBuilder request, String json) throws Exception {
		return mockMvc.perform(request.with(csrfToken(mockMvc)).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private static String studentJson(String campusId, String grade, String registeredAt, String motherPhone) {
		return """
				{"campusId":%s,"name":"%s","grade":%s,"registeredAt":%s,
				 "guardians":[{"name":"김어머니","relation":"MOTHER","phone":"%s"}]}
				""".formatted(campusId, uniqueName(), grade, registeredAt, motherPhone);
	}

}
