package com.jey.dongtanpass.web;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.auth.api.UserRole;
import org.junit.jupiter.api.AfterEach;
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

import static com.jey.TestCsrf.csrfToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 반대 방향(JEY 발급 → 연계 학원 사용) 패스의 할인율. 연계 학원과의 합의에 따라 바뀔 수 있어 관리자가 정한다.
// 발급할 때 이 값을 패스에 남기므로, 바꿔도 이미 발급한 패스는 그대로다(발급 시험에서 본다).
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CampusBenefitApiTest {

	private static final String BENEFIT = "/api/v1/dongtanpass/campus-benefit";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	// 같은 DB를 쓰는 다른 시험(발급)이 처음 값을 기대한다.
	@AfterEach
	void restore() {
		jdbc.update("UPDATE dongtanpass_campus_benefit SET discount_rate = 0.0500");
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 처음_할인율은_5퍼센트다() throws Exception {
		mockMvc.perform(get(BENEFIT)).andExpect(status().isOk()).andExpect(jsonPath("$.discountRate").value(0.05));
	}

	@Test
	@WithJeyUser(id = 900_501L, role = UserRole.ADMIN)
	void 관리자가_바꾸면_새_값으로_읽히고_감사_로그에_남는다() throws Exception {
		change("0.07").andExpect(status().isOk()).andExpect(jsonPath("$.discountRate").value(0.07));

		mockMvc.perform(get(BENEFIT)).andExpect(jsonPath("$.discountRate").value(0.07));
		assertThat(jdbc.queryForList("SELECT outcome, detail_json FROM core_audit_log"
				+ " WHERE action = 'dongtanpass.campus-benefit-changed' AND actor_id = 900501"))
			.singleElement()
			.satisfies(row -> {
				assertThat(row).containsEntry("outcome", "DONE");
				assertThat((String) row.get("detail_json")).contains("0.07");
			});
	}

	// 할인율은 비율이다(5%는 0.05). core의 할인 유형과 같은 규칙.
	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 규칙_밖의_할인율은_400이다() throws Exception {
		for (String rate : new String[] { "0", "-0.01", "1.5", "0.12345", "\"0.05\"", "null" }) {
			change(rate).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
		}
		mockMvc.perform(get(BENEFIT)).andExpect(jsonPath("$.discountRate").value(0.05));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1)
	void 직원은_보지도_바꾸지도_못한다() throws Exception {
		mockMvc.perform(get(BENEFIT)).andExpect(status().isForbidden());
		change("0.07").andExpect(status().isForbidden());
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = 1)
	void 연계_학원은_보지도_바꾸지도_못한다() throws Exception {
		mockMvc.perform(get(BENEFIT)).andExpect(status().isForbidden());
		change("0.07").andExpect(status().isForbidden());
	}

	private ResultActions change(String rate) throws Exception {
		return mockMvc.perform(put(BENEFIT).with(csrfToken(mockMvc))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"discountRate\": %s}".formatted(rate)));
	}

}
