package com.jey.dongtanpass.web;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.auth.api.UserRole;
import com.jey.dongtanpass.application.ConsentNotice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static com.jey.dongtanpass.DongtanpassFixtures.upsertPartner;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConsentNoticeApiTest {

	private static final String NOTICE = "/api/v1/dongtanpass/consent-notice";

	private static final long ACTIVE = 900_201L;

	private static final long INACTIVE = 900_202L;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void setUp() {
		upsertPartner(jdbc, ACTIVE, true);
		upsertPartner(jdbc, INACTIVE, false);
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = ACTIVE)
	void 지금_고지_문구와_버전을_준다() throws Exception {
		mockMvc.perform(get(NOTICE))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.version").value(ConsentNotice.CURRENT.version()))
			.andExpect(jsonPath("$.text").value(ConsentNotice.CURRENT.text()));
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = INACTIVE)
	void 꺼진_학원은_볼_수_없다() throws Exception {
		mockMvc.perform(get(NOTICE))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PARTNER_INACTIVE"));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1)
	void 직원은_볼_수_없다() throws Exception {
		mockMvc.perform(get(NOTICE)).andExpect(status().isForbidden());
	}

	// 관리자는 연계 학원이 아니다. 연계 학원의 화면을 대신 쓰지 않는다.
	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 관리자도_볼_수_없다() throws Exception {
		mockMvc.perform(get(NOTICE)).andExpect(status().isForbidden());
	}

}
