package com.jey.core.auth.web;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.auth.api.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class RoleAccessTest {

	@Autowired
	MockMvc mockMvc;

	// 403이면 프론트가 로그인 화면으로 보내지 않는다.
	@Test
	void 로그인하지_않으면_역할_표시가_있어도_401이다() throws Exception {
		mockMvc.perform(get("/api/v1/test-access/desk"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("COMMON_UNAUTHENTICATED"));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1)
	void 적힌_역할이면_통과한다() throws Exception {
		mockMvc.perform(get("/api/v1/test-access/desk")).andExpect(status().isOk());
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = 7)
	void 적히지_않은_역할이면_403_Problem_Details() throws Exception {
		mockMvc.perform(get("/api/v1/test-access/desk"))
				.andExpect(status().isForbidden())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.status").value(403))
				.andExpect(jsonPath("$.code").value("AUTH_ROLE_NOT_ALLOWED"))
				.andExpect(jsonPath("$.detail").value("이 기능을 사용할 권한이 없습니다."));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1)
	void 메서드의_표시가_클래스의_표시보다_우선한다_직원은_관리자_기능을_못_쓴다() throws Exception {
		mockMvc.perform(get("/api/v1/test-access/admin"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("AUTH_ROLE_NOT_ALLOWED"));
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 관리자는_관리자_기능을_쓴다() throws Exception {
		mockMvc.perform(get("/api/v1/test-access/admin")).andExpect(status().isOk());
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = 7)
	void 연계_학원은_연계_학원_기능을_쓴다() throws Exception {
		mockMvc.perform(get("/api/v1/test-access/partner")).andExpect(status().isOk());
	}

	// "관리자는 뭐든 된다"가 아니다. 관리자가 써야 하는 기능에는 ADMIN을 적는다.
	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 관리자도_적혀_있지_않으면_403이다() throws Exception {
		mockMvc.perform(get("/api/v1/test-access/partner"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("AUTH_ROLE_NOT_ALLOWED"));
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = 7)
	void 로그인한_누구나_쓰는_기능은_역할을_가리지_않는다() throws Exception {
		mockMvc.perform(get("/api/v1/test-access/any")).andExpect(status().isOk());
	}

	// 우리 역할이 아닌 권한(ROLE_USER)만 가진 로그인은 역할 검사를 통과하지 못한다.
	@Test
	@WithMockUser
	void 역할이_없는_로그인은_403이다() throws Exception {
		mockMvc.perform(get("/api/v1/test-access/desk"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("AUTH_ROLE_NOT_ALLOWED"));
	}

	// 누가 무엇을 하려다 막혔는지 남긴다. 계정 ID와 세션에 든 권한만 남기고 아이디와 이름은 남기지 않는다.
	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = 7, id = 4242)
	void 역할_거부는_로그에_남는다(CapturedOutput output) throws Exception {
		mockMvc.perform(get("/api/v1/test-access/desk")).andExpect(status().isForbidden());

		assertThat(output.getOut().lines().filter(line -> line.contains("account=4242"))).singleElement()
				.asString()
				.contains("접근 거부")
				.contains("code=AUTH_ROLE_NOT_ALLOWED")
				.contains("ROLE_PARTNER")
				.contains("GET /api/v1/test-access/desk");
		assertThat(output.getOut()).doesNotContain("test-partner").doesNotContain("테스트 사용자");
	}

	// 존재하지 않는 주소는 컨트롤러가 없으므로 접근 규칙과 무관하게 404다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1)
	void 없는_주소는_404다() throws Exception {
		mockMvc.perform(get("/api/v1/test-access/no-such-path"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));
	}

}
