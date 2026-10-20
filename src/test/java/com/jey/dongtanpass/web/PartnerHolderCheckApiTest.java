package com.jey.dongtanpass.web;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.concurrent.TimeUnit;

import com.jey.TestcontainersConfiguration;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.PhoneNumber;
import com.jey.core.shared.PhoneProtector;
import com.jey.dongtanpass.application.PassBenefits;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static com.jey.TestCsrf.csrfToken;
import static com.jey.core.member.MemberFixtures.randomPhone;
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

// 연계 학원이 등록 서류에 적은 학생 이름과 학부모 번호가 패스와 맞는지 미리 본다(데스크의 본인 확인과 같은 비교).
// 최종 판단은 사용 처리가 다시 한다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PartnerHolderCheckApiTest {

	private static final long PARTNER = 902_101L;

	private static final long OTHER_PARTNER = 902_102L;

	// application.yaml의 holder-check 한도(HolderCheckPropertiesTest가 이 값을 본다).
	private static final int HOLDER_CHECK_MAX = 30;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PassRepository passes;

	@Autowired
	PassBenefits passBenefits;

	@Autowired
	PhoneProtector phoneProtector;

	@Autowired
	Clock clock;

	@Autowired
	StringRedisTemplate redis;

	private PhoneNumber phone;

	private Pass pass;

	@BeforeEach
	void setUp() throws Exception {
		upsertPartner(jdbc, PARTNER, true);
		phone = randomPhone();
		pass = passes.saveAndFlush(campusPass(passBenefits.current().campusId(), newStudentId(), "김하윤",
				phoneProtector.fullIndex(phone), clock.instant(), new BigDecimal("0.05")));
		verifyAt(mockMvc, pass, PARTNER);
	}

	// 확인하지 않은 패스 ID로는 본인 확인을 할 수 없다(패스의 있음·주인을 알아내지 못하게). 다른 학원이 확인한 것도 같다.
	// 패스를 찾기 전에 막으므로, 있는 패스든 없는 패스든 같은 답이다.
	@Test
	void 이_학원이_확인하지_않은_패스는_다시_확인하라는_404다() throws Exception {
		Pass notVerified = passes.saveAndFlush(campusPass(passBenefits.current().campusId(), newStudentId(), "김하윤",
				phoneProtector.fullIndex(phone), clock.instant(), new BigDecimal("0.05")));
		upsertPartner(jdbc, OTHER_PARTNER, true);

		check(notVerified.getId(), body("김하윤", phone.value()), partner(PARTNER)).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PASS_NOT_VERIFIED"));
		check(pass.getId(), body("김하윤", phone.value()), partner(OTHER_PARTNER)).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PASS_NOT_VERIFIED"));
		check(Long.MAX_VALUE, body("김하윤", phone.value()), partner(PARTNER)).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PASS_NOT_VERIFIED"));
	}

	@Test
	void 이름과_번호가_맞으면_둘_다_참이다() throws Exception {
		check(pass.getId(), body("김 하윤", "010-0000-0000", phone.value()), partner(PARTNER))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.nameMatches").value(true))
			.andExpect(jsonPath("$.phoneMatches").value(true));
	}

	@Test
	void 틀린_쪽을_따로_알려_준다() throws Exception {
		check(pass.getId(), body("김하은", phone.value()), partner(PARTNER)).andExpect(jsonPath("$.nameMatches").value(false))
			.andExpect(jsonPath("$.phoneMatches").value(true));
		check(pass.getId(), body("김하윤", randomPhone().value()), partner(PARTNER))
			.andExpect(jsonPath("$.nameMatches").value(true))
			.andExpect(jsonPath("$.phoneMatches").value(false));
	}

	@Test
	void 확인한_뒤에_쓸_수_없게_된_패스는_409다() throws Exception {
		jdbc.update("UPDATE dongtanpass_pass SET status = 'CANCELED' WHERE id = ?", pass.getId());
		check(pass.getId(), body("김하윤", phone.value()), partner(PARTNER)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PASS_CANCELED"));
	}

	// 확인 기록은 정해진 시간이 지나면 사라진다. 사라진 뒤에는 확인하지 않은 패스와 같다.
	@Test
	void 확인_기록은_30분_안에_사라지고_사라지면_다시_확인해야_한다() throws Exception {
		String key = "jey:dongtanpass:partner-verified:" + PARTNER + ":" + pass.getId();
		Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
		assertThat(ttl).isPositive().isLessThanOrEqualTo(30 * 60);

		redis.delete(key);

		check(pass.getId(), body("김하윤", phone.value()), partner(PARTNER)).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PASS_NOT_VERIFIED"));
	}

	// 이름과 번호를 주소에 실어 보내면 앞단의 접근 로그에 남는다. 조용히 무시하지 않고 거부해서 드러나게 한다(ADR-0032).
	@Test
	void 쿼리_문자열이_실려_오면_400이고_세지_않는다() throws Exception {
		long account = newAccount();

		mockMvc.perform(post("/api/v1/dongtanpass/partner-redemptions/{id}/holder-check?studentName=김하윤", pass.getId())
			.with(securityContext(login(account, UserRole.PARTNER, null, PARTNER)))
			.with(csrfToken(mockMvc))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body("김하윤", phone.value())))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON_BAD_REQUEST"));
		assertThat(redis.hasKey("jey:auth:account-limit:dongtanpass.holder-check:" + account)).isFalse();
	}

	// 데스크와 같은 한도(계정마다, 맞든 틀리든). 번호를 넣어 보며 패스 주인을 알아내지 못하게 한다.
	@Test
	void 계정마다_정한_횟수를_넘으면_429다() throws Exception {
		SecurityContext account = login(newAccount(), UserRole.PARTNER, null, PARTNER);
		for (int i = 0; i < HOLDER_CHECK_MAX; i++) {
			check(pass.getId(), body("김하윤", randomPhone().value()), account).andExpect(status().isOk());
		}

		check(pass.getId(), body("김하윤", phone.value()), account).andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_HOLDER_CHECK_LIMITED"));
	}

	@Test
	void 직원은_연계_학원의_본인_확인을_쓰지_못한다() throws Exception {
		check(pass.getId(), body("김하윤", phone.value()), login(newAccount(), UserRole.STAFF, 1L, null))
			.andExpect(status().isForbidden());
	}

	private ResultActions check(long passId, String body, SecurityContext login) throws Exception {
		return mockMvc.perform(post("/api/v1/dongtanpass/partner-redemptions/{id}/holder-check", passId)
			.with(securityContext(login))
			.with(csrfToken(mockMvc))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private static String body(String name, String... phones) {
		return "{\"studentName\":\"" + name + "\",\"guardianPhones\":[\"" + String.join("\",\"", phones) + "\"]}";
	}

}
