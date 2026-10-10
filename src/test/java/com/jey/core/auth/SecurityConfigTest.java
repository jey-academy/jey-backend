package com.jey.core.auth;

import com.jey.TestCsrf;
import com.jey.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static com.jey.TestCsrf.csrfToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityConfigTest {

	// application-test.yaml의 jey.auth.allowed-origins와 같은 값
	private static final String ALLOWED_ORIGIN = "http://localhost:5173";

	@Autowired
	MockMvc mockMvc;

	@Test
	void API_명세는_인증_없이_열린다() throws Exception {
		mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
	}

	@Test
	void 헬스_체크는_인증_없이_열린다() throws Exception {
		mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}

	@Test
	void 그_외_경로는_인증이_없으면_401_Problem_Details() throws Exception {
		mockMvc.perform(get("/api/v1/anything"))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.title").value("Unauthorized"))
				.andExpect(jsonPath("$.status").value(401))
				.andExpect(jsonPath("$.detail").value("로그인이 필요합니다."))
				.andExpect(jsonPath("$.code").value("COMMON_UNAUTHENTICATED"));
	}

	@Test
	@WithMockUser
	void CSRF_토큰_없는_변경_요청은_403_Problem_Details() throws Exception {
		mockMvc.perform(post("/api/v1/anything"))
				.andExpect(status().isForbidden())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.status").value(403))
				.andExpect(jsonPath("$.detail").value("권한이 없습니다."))
				.andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
	}

	// CSRF 필터가 인증 검사보다 먼저 돌아서, 인증이 없어도 401이 아니라 403이다.
	@Test
	void 인증_없는_변경_요청도_CSRF_토큰이_없으면_403() throws Exception {
		mockMvc.perform(post("/api/v1/anything"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
	}

	@Test
	void CSRF_토큰이_있어도_인증이_없으면_401() throws Exception {
		mockMvc.perform(post("/api/v1/anything").with(csrfToken(mockMvc)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("COMMON_UNAUTHENTICATED"));
	}

	// 인증 없는 요청마다 세션을 만들면 Redis에 세션이 쌓인다.
	@Test
	void 인증_없는_요청은_세션을_만들지_않는다() throws Exception {
		mockMvc.perform(get("/api/v1/anything"))
				.andExpect(status().isUnauthorized())
				.andExpect(cookie().doesNotExist("SESSION"));
	}

	// 프론트가 JavaScript로 읽어 헤더에 실어야 하므로 httpOnly가 아니다.
	@Test
	void CSRF_토큰을_쿠키로_발급한다() throws Exception {
		mockMvc.perform(get("/api/v1/auth/csrf"))
				.andExpect(status().isNoContent())
				.andExpect(cookie().exists("XSRF-TOKEN"))
				.andExpect(cookie().httpOnly("XSRF-TOKEN", false))
				.andExpect(cookie().sameSite("XSRF-TOKEN", "Lax"))
				.andExpect(cookie().doesNotExist("SESSION"));
	}

	// 프론트가 실제로 하는 방식: 쿠키로 받은 값을 그대로 X-XSRF-TOKEN 헤더에 싣는다.
	@Test
	void 쿠키로_받은_CSRF_토큰을_헤더로_보내면_CSRF_검사를_통과한다() throws Exception {
		var csrfCookie = TestCsrf.issue(mockMvc);

		mockMvc.perform(post("/api/v1/anything").cookie(csrfCookie).header("X-XSRF-TOKEN", csrfCookie.getValue()))
				.andExpect(status().isUnauthorized());

		// 쿠키와 헤더의 값이 다르면 통과하지 못한다.
		mockMvc.perform(post("/api/v1/anything").cookie(csrfCookie).header("X-XSRF-TOKEN", "wrong-token"))
				.andExpect(status().isForbidden());
	}

	@Test
	void 허용한_출처의_사전_요청은_통과한다() throws Exception {
		mockMvc.perform(options("/api/v1/auth/login")
						.header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type, x-xsrf-token"))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
	}

	@Test
	void 허용한_출처의_요청에는_CORS_헤더를_붙인다() throws Exception {
		mockMvc.perform(get("/api/v1/auth/csrf").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
				.andExpect(status().isNoContent())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
	}

	@Test
	void 허용하지_않은_출처는_거부한다() throws Exception {
		mockMvc.perform(options("/api/v1/auth/login")
						.header(HttpHeaders.ORIGIN, "https://evil.example")
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));

		mockMvc.perform(get("/api/v1/auth/csrf").header(HttpHeaders.ORIGIN, "https://evil.example"))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
	}

	// 변환기가 응답을 쓰지 못하면 상태가 200으로 남는다. 그때는 본문이 없더라도 실패 상태로 응답해야 한다.
	@Test
	void 예외_변환에_실패하면_실패_상태로_응답한다() throws Exception {
		var response = new MockHttpServletResponse();

		SecurityConfig.delegate((request, res, handler, ex) -> null,
				new MockHttpServletRequest("GET", "/api/v1/anything"), response,
				new BadCredentialsException("x"), HttpStatus.UNAUTHORIZED);

		assertThat(response.getStatus()).isEqualTo(401);
	}

}
