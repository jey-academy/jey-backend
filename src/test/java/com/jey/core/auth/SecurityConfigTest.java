package com.jey.core.auth;

import com.jey.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityConfigTest {

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

}
