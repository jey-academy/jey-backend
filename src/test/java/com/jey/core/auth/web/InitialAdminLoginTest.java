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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 첫 관리자 설정이 있는 상태로 서버를 띄워, 설정값 읽기 → 시작 시 계정 생성 → 그 계정으로 로그인까지 한 번에 확인한다.
// 설정값이 다른 테스트와 달라서 이 테스트만의 컨텍스트와 빈 DB가 따로 뜬다. 계정이 0개인 상태가 필요하므로 의도한 것이다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
		"jey.auth.initial-admin.login-id=Owner-E2E",
		"jey.auth.initial-admin.password=first-admin-password",
		"jey.auth.initial-admin.name=박원장",
		"jey.auth.initial-admin.required=true" })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InitialAdminLoginTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	UserRepository users;

	@Test
	void 서버가_뜨면_첫_관리자가_생기고_그_계정으로_로그인된다() throws Exception {
		User admin = users.findByLoginId("owner-e2e").orElseThrow();
		assertThat(admin.getCreatedBy()).isNull();
		assertThat(users.count()).isEqualTo(1);

		mockMvc.perform(post("/api/v1/auth/login").with(csrfToken(mockMvc))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"loginId\":\"owner-e2e\",\"password\":\"first-admin-password\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(admin.getId()))
				.andExpect(jsonPath("$.name").value("박원장"))
				.andExpect(jsonPath("$.role").value("ADMIN"))
				.andExpect(jsonPath("$.campusId").value(nullValue()));
	}

}
