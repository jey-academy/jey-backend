package com.jey.dongtanpass.web;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import com.jey.TestcontainersConfiguration;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.PhoneNumber;
import com.jey.core.shared.PhoneProtector;
import com.jey.dongtanpass.application.PartnerVerifiedPasses;
import com.jey.dongtanpass.application.PassBenefits;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassRepository;
import com.jey.dongtanpass.domain.PassStatus;
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
import static com.jey.dongtanpass.web.PartnerPassFixtures.partnerPass;
import static com.jey.dongtanpass.web.PartnerPassFixtures.verifyAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 연계 학원이 JEY 발급 패스를 사용 처리한다. 이름·번호를 서버가 다시 맞춰 보고, 같은 학생은 이 학원에서 한 번만 할인받는다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PartnerRedeemApiTest {

	private static final long PARTNER = 902_301L;

	private static final long OTHER_PARTNER = 902_302L;

	private static final long INACTIVE = 902_303L;

	// application.yaml의 redeem-limit 한도(RedeemLimitPropertiesTest가 이 값을 본다).
	private static final int REDEEM_MAX = 30;

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
	PartnerVerifiedPasses verifiedPasses;

	@Autowired
	StringRedisTemplate redis;

	private PhoneNumber phone;

	@BeforeEach
	void setUp() {
		upsertPartner(jdbc, PARTNER, true);
		upsertPartner(jdbc, OTHER_PARTNER, true);
		upsertPartner(jdbc, INACTIVE, false);
		phone = randomPhone();
	}

	@Test
	void 사용_처리하면_금액과_기록을_남기고_패스는_사용됨이_된다() throws Exception {
		long student = newStudentId();
		Pass pass = save(student);

		redeem(pass.getId(), body("김 하윤", "285555"), partner(PARTNER)).andExpect(status().isOk())
			.andExpect(jsonPath("$.passId").value(pass.getId()))
			.andExpect(jsonPath("$.originalFee").value(285555))
			.andExpect(jsonPath("$.discountRate").value(0.05))
			.andExpect(jsonPath("$.discountAmount").value(14278))
			.andExpect(jsonPath("$.payableAmount").value(271277))
			.andExpect(jsonPath("$.redeemedAt").isString());

		assertThat(passes.findById(pass.getId()).orElseThrow().getStatus()).isEqualTo(PassStatus.REDEEMED);
		Map<String, Object> row = jdbc.queryForMap("SELECT * FROM dongtanpass_partner_redemption WHERE pass_id = ?",
				pass.getId());
		assertThat(row).containsEntry("partner_id", PARTNER).containsEntry("student_id", student);
		assertThat(row.get("payable_amount").toString()).isEqualTo("271277");
	}

	// 이벤트를 받은 감사 리스너가 남긴다. 행위자는 연계 학원 계정, 대상은 패스, 봉투의 지점은 발급 지점이다.
	@Test
	void 사용_처리는_감사_로그에_partner_pass_redeemed로_남는다() throws Exception {
		long account = newAccount();
		Pass pass = save(newStudentId());

		redeem(pass.getId(), body("김하윤", "280000"), login(account, UserRole.PARTNER, null, PARTNER))
			.andExpect(status().isOk());

		await().atMost(Duration.ofSeconds(10))
			.untilAsserted(() -> assertThat(jdbc.queryForList("SELECT actor_id, campus_id FROM core_audit_log"
					+ " WHERE action = 'dongtanpass.partner-pass-redeemed' AND target_type = 'pass' AND target_id = ?",
					pass.getId()))
				.singleElement()
				.satisfies(audit -> assertThat(audit).containsEntry("actor_id", account)
					.containsEntry("campus_id", passBenefits.current().campusId())));
	}

	@Test
	void 같은_키로_다시_보내면_같은_응답이고_기록은_하나다() throws Exception {
		Pass pass = save(newStudentId());
		SecurityContext account = partner(PARTNER);
		String key = UUID.randomUUID().toString();

		String first = redeemWithKey(pass.getId(), body("김하윤", "280000"), account, key).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		String second = redeemWithKey(pass.getId(), body("김하윤", "280000"), account, key).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();

		assertThat(second).isEqualTo(first);
		assertThat(redemptionCount(pass)).isEqualTo(1);
	}

	@Test
	void 이름이나_번호가_패스와_다르면_409이고_기록이_없다() throws Exception {
		Pass pass = save(newStudentId());

		redeem(pass.getId(), body("김하은", "280000"), partner(PARTNER)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_HOLDER_NAME_MISMATCH"));
		redeem(pass.getId(), bodyWithPhone("김하윤", randomPhone().value(), "280000"), partner(PARTNER))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_HOLDER_PHONE_MISMATCH"));

		assertThat(redemptionCount(pass)).isZero();
		assertThat(passes.findById(pass.getId()).orElseThrow().getStatus()).isEqualTo(PassStatus.ISSUED);
	}

	@Test
	void 같은_패스는_두_번_쓰지_못한다() throws Exception {
		Pass pass = save(newStudentId());
		verifyAt(mockMvc, pass, OTHER_PARTNER);
		redeem(pass.getId(), body("김하윤", "280000"), partner(PARTNER)).andExpect(status().isOk());

		redeem(pass.getId(), body("김하윤", "280000"), partner(OTHER_PARTNER)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PASS_ALREADY_REDEEMED"));
	}

	// 다음 달에 받은 패스로 같은 학원에 다시 와도 할인하지 않는다. 다른 학원에서는 새 학생이다.
	@Test
	void 같은_학생은_이_학원에서_한_번만_할인받고_다른_학원에서는_따로다() throws Exception {
		long student = newStudentId();
		redeem(save(student).getId(), body("김하윤", "280000"), partner(PARTNER)).andExpect(status().isOk());
		// 이 학원의 확인은 이미 할인받은 학생이라 막힌다(그 거부는 확인 시험이 본다). 사용 처리의 거부를 보려고 확인을 직접 적어 둔다.
		Pass nextMonth = saveNotVerified(student);
		verifiedPasses.remember(PARTNER, nextMonth.getId());
		verifyAt(mockMvc, nextMonth, OTHER_PARTNER);

		redeem(nextMonth.getId(), body("김하윤", "280000"), partner(PARTNER)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_STUDENT_ALREADY_USED_AT_PARTNER"));
		redeem(nextMonth.getId(), body("김하윤", "280000"), partner(OTHER_PARTNER)).andExpect(status().isOk());
	}

	@Test
	void 연계_학원이_발급한_패스와_규칙_밖의_수강료는_거부한다() throws Exception {
		// 연계 학원 발급 패스는 확인에서 막히므로, 사용 처리의 거부를 보려고 확인을 직접 적어 둔다.
		Pass partnerIssued = passes.saveAndFlush(partnerPass(PARTNER, "김하윤", phoneProtector.fullIndex(phone),
				clock.instant()));
		verifiedPasses.remember(PARTNER, partnerIssued.getId());
		redeem(partnerIssued.getId(), body("김하윤", "280000"), partner(PARTNER)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PASS_FOR_CAMPUS_USE"));

		redeem(save(newStudentId()).getId(), body("김하윤", "0"), partner(PARTNER)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_INVALID_ORIGINAL_FEE"));
	}

	@Test
	void 꺼진_학원과_직원과_관리자는_사용_처리하지_못한다() throws Exception {
		long passId = save(newStudentId()).getId();

		redeem(passId, body("김하윤", "280000"), partner(INACTIVE)).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PARTNER_INACTIVE"));
		redeem(passId, body("김하윤", "280000"), login(newAccount(), UserRole.STAFF, 1L, null))
			.andExpect(status().isForbidden());
		redeem(passId, body("김하윤", "280000"), login(newAccount(), UserRole.ADMIN, null, null))
			.andExpect(status().isForbidden());
	}

	// 맞든 틀리든 센다(이름·번호 대조 결과로 패스 주인을 훑지 못하게). 데스크와 같은 한도.
	@Test
	void 계정마다_정한_횟수를_넘으면_429다() throws Exception {
		SecurityContext account = partner(PARTNER);
		long passId = save(newStudentId()).getId();
		for (int i = 0; i < REDEEM_MAX; i++) {
			redeem(passId, body("김하은", "280000"), account).andExpect(status().isConflict());
		}

		redeem(passId, body("김하윤", "280000"), account).andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_REDEEM_LIMITED"));
	}

	// 이 학원(PARTNER)이 확인해 둔 패스. 다른 학원에서 쓰는 시험은 그 학원도 확인한다.
	private Pass save(long student) throws Exception {
		Pass pass = saveNotVerified(student);
		verifyAt(mockMvc, pass, PARTNER);
		return pass;
	}

	private Pass saveNotVerified(long student) {
		return passes.saveAndFlush(campusPass(passBenefits.current().campusId(), student, "김하윤",
				phoneProtector.fullIndex(phone), clock.instant(), new BigDecimal("0.05")));
	}

	@Test
	void 이_학원이_확인하지_않은_패스는_다시_확인하라는_404다() throws Exception {
		Pass notVerified = saveNotVerified(newStudentId());

		redeem(notVerified.getId(), body("김하윤", "280000"), partner(PARTNER)).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PASS_NOT_VERIFIED"));
		assertThat(redemptionCount(notVerified)).isZero();
	}

	// 다시 확인하면 같은 요청이 지나가므로 잠시 막은 거부다. 같은 키로 다시 보내도 처음의 404를 되돌려주지 않고 처리한다.
	@Test
	void 확인하지_않아_막힌_요청은_다시_확인한_뒤_같은_키로_보내면_처리된다() throws Exception {
		Pass pass = saveNotVerified(newStudentId());
		SecurityContext account = partner(PARTNER);
		String key = UUID.randomUUID().toString();
		redeemWithKey(pass.getId(), body("김하윤", "280000"), account, key).andExpect(status().isNotFound());

		verifyAt(mockMvc, pass, PARTNER);

		redeemWithKey(pass.getId(), body("김하윤", "280000"), account, key).andExpect(status().isOk());
		assertThat(redemptionCount(pass)).isEqualTo(1);
	}

	// 확인한 뒤에 취소되거나 기간이 지난 패스는 사용 처리가 다시 본다(확인 기록은 30분 남는다). 기록을 남기지 않는다.
	@Test
	void 확인한_뒤에_취소되거나_기간이_지난_패스는_409이고_기록이_없다() throws Exception {
		Pass canceled = save(newStudentId());
		jdbc.update("UPDATE dongtanpass_pass SET status = 'CANCELED' WHERE id = ?", canceled.getId());
		Pass expired = save(newStudentId());
		jdbc.update("UPDATE dongtanpass_pass SET expires_on = ? WHERE id = ?",
				LocalDate.now(clock).minusDays(1), expired.getId());

		redeem(canceled.getId(), body("김하윤", "280000"), partner(PARTNER)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PASS_CANCELED"));
		redeem(expired.getId(), body("김하윤", "280000"), partner(PARTNER)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PASS_EXPIRED"));

		assertThat(redemptionCount(canceled)).isZero();
		assertThat(redemptionCount(expired)).isZero();
		assertThat(passes.findById(canceled.getId()).orElseThrow().getStatus()).isEqualTo(PassStatus.CANCELED);
		assertThat(passes.findById(expired.getId()).orElseThrow().getStatus()).isEqualTo(PassStatus.ISSUED);
	}

	// 이름과 번호를 주소에 실어 보내면 앞단의 접근 로그에 남는다. 조용히 무시하지 않고 거부해서 드러나게 한다(ADR-0032).
	@Test
	void 쿼리_문자열이_실려_오면_400이고_세지도_기록하지도_않는다() throws Exception {
		Pass pass = save(newStudentId());
		long account = newAccount();

		mockMvc.perform(post("/api/v1/dongtanpass/partner-redemptions/{id}/redeem?studentName=김하윤", pass.getId())
			.with(securityContext(login(account, UserRole.PARTNER, null, PARTNER)))
			.with(csrfToken(mockMvc))
			.header("Idempotency-Key", UUID.randomUUID().toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body("김하윤", "280000")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON_BAD_REQUEST"));

		assertThat(redemptionCount(pass)).isZero();
		assertThat(redis.hasKey("jey:auth:account-limit:dongtanpass.redeem:" + account)).isFalse();
	}

	private String body(String name, String fee) {
		return bodyWithPhone(name, phone.value(), fee);
	}

	private static String bodyWithPhone(String name, String guardianPhone, String fee) {
		return "{\"studentName\":\"" + name + "\",\"guardianPhones\":[\"" + guardianPhone + "\"],\"originalFee\":" + fee
				+ "}";
	}

	private ResultActions redeem(long passId, String body, SecurityContext login) throws Exception {
		return redeemWithKey(passId, body, login, UUID.randomUUID().toString());
	}

	private ResultActions redeemWithKey(long passId, String body, SecurityContext login, String key) throws Exception {
		return mockMvc.perform(post("/api/v1/dongtanpass/partner-redemptions/{id}/redeem", passId)
			.with(securityContext(login))
			.with(csrfToken(mockMvc))
			.header("Idempotency-Key", key)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private long redemptionCount(Pass pass) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM dongtanpass_partner_redemption WHERE pass_id = ?", Long.class,
				pass.getId());
	}

}
