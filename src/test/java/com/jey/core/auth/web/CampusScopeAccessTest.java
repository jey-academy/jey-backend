package com.jey.core.auth.web;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CampusScopeAccessTest {

	@Autowired
	MockMvc mockMvc;

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1)
	void 직원은_소속_지점의_묶인_자료를_다룬다() throws Exception {
		mockMvc.perform(get("/api/v1/test-access/invoices/1")).andExpect(status().isOk());
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1)
	void 직원이_다른_지점의_묶인_자료를_다루면_403_Problem_Details() throws Exception {
		mockMvc.perform(get("/api/v1/test-access/invoices/2"))
				.andExpect(status().isForbidden())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.code").value("AUTH_CAMPUS_NOT_ALLOWED"))
				.andExpect(jsonPath("$.detail").value("이 지점의 자료를 다룰 권한이 없습니다."));
	}

	// 직원은 기본적으로 전 지점의 자료를 다룬다. 지점 범위는 묶기로 한 자료에만 걸린다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1)
	void 묶지_않은_자료는_직원도_다른_지점_것을_다룬다() throws Exception {
		mockMvc.perform(get("/api/v1/test-access/students/2")).andExpect(status().isOk());
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1)
	void 직원의_목록은_지점을_고르지_않아도_소속_지점이다() throws Exception {
		mockMvc.perform(get("/api/v1/test-access/invoices")).andExpect(content().string("campus:1"));
		mockMvc.perform(get("/api/v1/test-access/invoices").param("campusId", "1"))
				.andExpect(content().string("campus:1"));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1)
	void 직원이_목록에서_다른_지점을_고르면_403이다() throws Exception {
		mockMvc.perform(get("/api/v1/test-access/invoices").param("campusId", "2"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("AUTH_CAMPUS_NOT_ALLOWED"));
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 관리자의_목록은_전_지점이고_지점을_고르면_그_지점이다() throws Exception {
		mockMvc.perform(get("/api/v1/test-access/invoices")).andExpect(content().string("all"));
		mockMvc.perform(get("/api/v1/test-access/invoices").param("campusId", "2"))
				.andExpect(content().string("campus:2"));
		mockMvc.perform(get("/api/v1/test-access/invoices/2")).andExpect(status().isOk());
	}

}
