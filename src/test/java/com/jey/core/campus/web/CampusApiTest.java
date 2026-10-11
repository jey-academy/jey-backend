package com.jey.core.campus.web;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.auth.api.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CampusApiTest {

	@Autowired
	MockMvc mockMvc;

	// 계정의 campus_id가 이 ID를 가리킨다. 마이그레이션이 ID를 고정해서 넣는다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1)
	void 지점은_두_곳이고_ID가_고정돼_있다() throws Exception {
		mockMvc.perform(get("/api/v1/campuses"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].id").value(1))
				.andExpect(jsonPath("$[0].name").value("동탄관"))
				.andExpect(jsonPath("$[1].id").value(2))
				.andExpect(jsonPath("$[1].name").value("능동관"));
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 관리자도_지점_목록을_본다() throws Exception {
		mockMvc.perform(get("/api/v1/campuses")).andExpect(status().isOk());
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = 7)
	void 연계_학원은_지점_목록을_보지_못한다() throws Exception {
		mockMvc.perform(get("/api/v1/campuses"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("AUTH_ROLE_NOT_ALLOWED"));
	}

	@Test
	void 로그인하지_않으면_401이다() throws Exception {
		mockMvc.perform(get("/api/v1/campuses")).andExpect(status().isUnauthorized());
	}

}
