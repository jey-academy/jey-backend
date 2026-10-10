package com.jey.core.auth.web;

import com.jey.TestcontainersConfiguration;
import com.jey.core.auth.domain.User;
import com.jey.core.auth.domain.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static com.jey.TestCsrf.csrfToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 첫 관리자 설정이 있는 상태로 서버를 띄워, 설정값 읽기 → 시작 시 계정 생성 → 그 계정으로 로그인까지 한 번에 확인한다.
// 설정값이 다른 테스트와 달라서 이 테스트만의 컨텍스트와 빈 DB가 따로 뜬다. 계정이 0개인 상태가 필요하므로 의도한 것이다.
// (DB 컨테이너가 컨텍스트마다 새로 뜨는 빈이라 그렇다. 컨테이너를 공유하게 바꾸면 이 전제가 깨진다.)
// 운영처럼 Secure 쿠키와 쿠키 도메인을 켠 상태로도 띄워서, 실제 응답의 쿠키에 그 설정이 반영되는지 함께 본다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
		"jey.auth.initial-admin.login-id=Owner-E2E",
		"jey.auth.initial-admin.password=first-admin-password",
		"jey.auth.initial-admin.name=박원장",
		"jey.auth.initial-admin.required=true",
		"jey.auth.secure-cookies=true",
		"jey.auth.cookie-domain=jey.example" })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InitialAdminLoginTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	UserRepository users;

	@Test
	void 서버가_뜨면_첫_관리자가_생기고_그_계정으로_로그인된다() throws Exception {
		User admin = users.findByLoginId("owner-e2e")
				.orElseThrow(() -> new AssertionError("서버가 뜰 때 첫 관리자가 만들어지지 않았다"));
		assertThat(admin.getCreatedBy()).isNull();
		assertThat(users.count()).as("이 컨텍스트의 DB에는 첫 관리자만 있어야 한다").isEqualTo(1);

		mockMvc.perform(post("/api/v1/auth/login").with(csrfToken(mockMvc))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"loginId\":\"owner-e2e\",\"password\":\"first-admin-password\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(admin.getId()))
				.andExpect(jsonPath("$.name").value("박원장"))
				.andExpect(jsonPath("$.role").value("ADMIN"))
				.andExpect(jsonPath("$.campusId").value(nullValue()))
				.andExpect(cookie().secure("SESSION", true))
				.andExpect(cookie().httpOnly("SESSION", true))
				// 세션 쿠키는 API 호스트 전용이다. 쿠키 도메인 설정은 CSRF 쿠키에만 적용된다.
				.andExpect(cookie().domain("SESSION", (String) null));
	}

	@Test
	void 운영_설정이면_CSRF_쿠키에_Secure와_도메인이_붙는다() throws Exception {
		mockMvc.perform(get("/api/v1/auth/csrf"))
				.andExpect(status().isNoContent())
				.andExpect(cookie().secure("XSRF-TOKEN", true))
				.andExpect(cookie().domain("XSRF-TOKEN", "jey.example"))
				.andExpect(cookie().httpOnly("XSRF-TOKEN", false));
	}

}
