package com.jey.dongtanpass.web;

import java.math.BigDecimal;
import java.time.Clock;

import com.jey.TestcontainersConfiguration;
import com.jey.core.auth.api.UserRole;
import com.jey.dongtanpass.application.PassBenefits;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static com.jey.TestCsrf.csrfToken;
import static com.jey.dongtanpass.DongtanpassFixtures.upsertPartner;
import static com.jey.dongtanpass.web.PartnerPassFixtures.campusPass;
import static com.jey.dongtanpass.web.PartnerPassFixtures.login;
import static com.jey.dongtanpass.web.PartnerPassFixtures.newAccount;
import static com.jey.dongtanpass.web.PartnerPassFixtures.newStudentId;
import static com.jey.dongtanpass.web.PartnerPassFixtures.partner;
import static com.jey.dongtanpass.web.PartnerPassFixtures.verifyAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 연계 학원이 원래 수강료(첫 달 한 달 치)를 넣으면 받을 금액을 미리 본다. 기록은 남기지 않는다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PartnerQuoteApiTest {

	private static final long PARTNER = 902_201L;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PassRepository passes;

	@Autowired
	PassBenefits passBenefits;

	@Autowired
	Clock clock;

	@BeforeEach
	void setUp() {
		upsertPartner(jdbc, PARTNER, true);
	}

	@Test
	void 원래_수강료로_할인율과_할인액과_받을_금액을_준다() throws Exception {
		Pass pass = save(new BigDecimal("0.05"));

		quote(pass.getId(), "280000", partner(PARTNER)).andExpect(status().isOk())
			.andExpect(jsonPath("$.originalFee").value(280000))
			.andExpect(jsonPath("$.discountRate").value(0.05))
			.andExpect(jsonPath("$.discountAmount").value(14000))
			.andExpect(jsonPath("$.payableAmount").value(266000));
		assertThat(pass.getStatus()).isEqualTo(passes.findById(pass.getId()).orElseThrow().getStatus());
	}

	@Test
	void 받을_금액에서_1원_미만을_버린다() throws Exception {
		quote(save(new BigDecimal("0.05")).getId(), "285555", partner(PARTNER))
			.andExpect(jsonPath("$.payableAmount").value(271277))
			.andExpect(jsonPath("$.discountAmount").value(14278));
	}

	// 할인율은 패스에 남은 발급 때 값이다(설정을 바꿔도 이 패스는 그대로).
	@Test
	void 패스에_남은_할인율로_계산한다() throws Exception {
		Pass pass = save(new BigDecimal("0.0725"));
		jdbc.update("UPDATE dongtanpass_campus_benefit SET discount_rate = 0.0900");
		try {
			quote(pass.getId(), "300000", partner(PARTNER)).andExpect(jsonPath("$.discountRate").value(0.0725))
				.andExpect(jsonPath("$.payableAmount").value(278250));
		}
		finally {
			jdbc.update("UPDATE dongtanpass_campus_benefit SET discount_rate = 0.0500");
		}
	}

	@Test
	void 규칙_밖의_수강료는_400이다() throws Exception {
		long passId = save(new BigDecimal("0.05")).getId();
		for (String fee : new String[] { "0", "1.5", "10000001" }) {
			quote(passId, fee, partner(PARTNER)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("DONGTANPASS_INVALID_ORIGINAL_FEE"));
		}
		// 금액으로 읽을 수 없는 값은 그 칸의 400이다(문자열, 음수, 빈 값).
		for (String fee : new String[] { "\"280000\"", "-1", "null" }) {
			quote(passId, fee, partner(PARTNER)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
		}
	}

	@Test
	void 확인한_뒤에_쓸_수_없게_된_패스는_409다() throws Exception {
		Pass canceled = save(new BigDecimal("0.05"));
		jdbc.update("UPDATE dongtanpass_pass SET status = 'CANCELED' WHERE id = ?", canceled.getId());

		quote(canceled.getId(), "280000", partner(PARTNER)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PASS_CANCELED"));
	}

	@Test
	void 직원은_금액_미리_보기를_쓰지_못한다() throws Exception {
		quote(save(new BigDecimal("0.05")).getId(), "280000", login(newAccount(), UserRole.STAFF, 1L, null))
			.andExpect(status().isForbidden());
	}

	private Pass save(BigDecimal rate) throws Exception {
		Pass pass = passes.saveAndFlush(campusPass(passBenefits.current().campusId(), newStudentId(), "김하윤", "idx",
				clock.instant(), rate));
		verifyAt(mockMvc, pass, PARTNER);
		return pass;
	}

	// 확인하지 않은 패스 ID로는 할인율·상태를 알아내지 못한다.
	@Test
	void 이_학원이_확인하지_않은_패스는_없는_패스와_같은_404다() throws Exception {
		Pass notVerified = passes.saveAndFlush(campusPass(passBenefits.current().campusId(), newStudentId(), "김하윤",
				"idx", clock.instant(), new BigDecimal("0.05")));

		quote(notVerified.getId(), "280000", partner(PARTNER)).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PASS_NOT_FOUND"));
	}

	private ResultActions quote(long passId, String fee, SecurityContext login) throws Exception {
		return mockMvc.perform(post("/api/v1/dongtanpass/partner-redemptions/{id}/quote", passId)
			.with(securityContext(login))
			.with(csrfToken(mockMvc))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"originalFee\": " + fee + "}"));
	}

}
