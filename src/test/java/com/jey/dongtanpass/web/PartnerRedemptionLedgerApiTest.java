package com.jey.dongtanpass.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.ThreadLocalRandom;

import com.jey.TestcontainersConfiguration;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.Money;
import com.jey.dongtanpass.application.PassBenefits;
import com.jey.dongtanpass.domain.PartnerDiscount;
import com.jey.dongtanpass.domain.PartnerRedemption;
import com.jey.dongtanpass.domain.PartnerRedemptionRepository;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import static com.jey.dongtanpass.DongtanpassFixtures.upsertPartner;
import static com.jey.dongtanpass.web.PartnerPassFixtures.SEOUL;
import static com.jey.dongtanpass.web.PartnerPassFixtures.campusPass;
import static com.jey.dongtanpass.web.PartnerPassFixtures.login;
import static com.jey.dongtanpass.web.PartnerPassFixtures.newAccount;
import static com.jey.dongtanpass.web.PartnerPassFixtures.newStudentId;
import static com.jey.dongtanpass.web.PartnerPassFixtures.partner;
import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 연계 학원의 JEY 패스 사용 내역. 연계 학원은 자기 학원 것만, 관리자는 모든 학원 것을 본다.
// 같은 DB에 다른 시험의 기록이 쌓이므로 시험마다 다른 먼 날짜에 기록을 만들고 그날만 본다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PartnerRedemptionLedgerApiTest {

	private static final String LIST = "/api/v1/dongtanpass/partner-redemptions";

	private static final long PARTNER = 902_501L;

	private static final long OTHER_PARTNER = 902_502L;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PassRepository passes;

	@Autowired
	PartnerRedemptionRepository partnerRedemptions;

	@Autowired
	PassBenefits passBenefits;

	@Autowired
	TransactionTemplate transaction;

	private LocalDate day;

	@BeforeEach
	void setUp() {
		upsertPartner(jdbc, PARTNER, true);
		upsertPartner(jdbc, OTHER_PARTNER, true);
		day = LocalDate.of(2045, 1, 1).plusDays(ThreadLocalRandom.current().nextInt(3000));
	}

	@Test
	void 연계_학원은_자기_학원의_기록만_최신_순으로_본다() throws Exception {
		PartnerRedemption older = redeem(PARTNER, "김하윤", 280000, day.atTime(10, 0));
		PartnerRedemption newer = redeem(PARTNER, "이서준", 285555, day.atTime(15, 0));
		redeem(OTHER_PARTNER, "박지우", 280000, day.atTime(12, 0));

		list(partner(PARTNER), null).andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(2))
			.andExpect(jsonPath("$.items[*].id").value(contains(newer.getId().intValue(), older.getId().intValue())))
			.andExpect(jsonPath("$.items[0].partnerId").value(PARTNER))
			.andExpect(jsonPath("$.items[0].partnerName").value("테스트학원-" + PARTNER))
			.andExpect(jsonPath("$.items[0].studentName").value("이서준"))
			.andExpect(jsonPath("$.items[0].originalFee").value(285555))
			.andExpect(jsonPath("$.items[0].discountRate").value(0.05))
			.andExpect(jsonPath("$.items[0].discountAmount").value(14278))
			.andExpect(jsonPath("$.items[0].payableAmount").value(271277))
			.andExpect(jsonPath("$.items[0].redeemedOn").value(day.toString()));
	}

	// 다른 학원 ID를 주면 조용히 자기 학원으로 바꾸지 않고 거부한다(화면의 실수가 드러나게).
	@Test
	void 연계_학원이_다른_학원을_고르면_403이다() throws Exception {
		list(partner(PARTNER), OTHER_PARTNER).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_OTHER_PARTNER_NOT_ALLOWED"));
		list(partner(PARTNER), PARTNER).andExpect(status().isOk());
	}

	@Test
	void 관리자는_모든_학원의_기록을_보고_학원으로_거른다() throws Exception {
		redeem(PARTNER, "김하윤", 280000, day.atTime(10, 0));
		PartnerRedemption other = redeem(OTHER_PARTNER, "박지우", 280000, day.atTime(12, 0));
		SecurityContext admin = login(newAccount(), UserRole.ADMIN, null, null);

		list(admin, null).andExpect(jsonPath("$.totalElements").value(2));
		list(admin, OTHER_PARTNER).andExpect(jsonPath("$.items[*].id").value(contains(other.getId().intValue())));
	}

	@Test
	void 기간이_틀리면_400이다() throws Exception {
		mockMvc.perform(get(LIST).with(securityContext(partner(PARTNER)))
			.param("from", day.toString())
			.param("to", day.minusDays(1).toString())).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_INVALID_PERIOD"));
		mockMvc.perform(get(LIST).with(securityContext(partner(PARTNER)))
			.param("from", day.toString())
			.param("to", day.plusYears(1).plusDays(1).toString())).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PERIOD_TOO_LONG"));
	}

	// 두 방향의 사용 기록은 따로다. 동탄관 데스크의 사용 내역에 연계 학원 기록이 섞이지 않는다.
	@Test
	void 동탄관_데스크의_사용_내역에는_섞이지_않는다() throws Exception {
		redeem(PARTNER, "김하윤", 280000, day.atTime(10, 0));

		mockMvc.perform(get("/api/v1/dongtanpass/redemptions").with(securityContext(login(newAccount(), UserRole.ADMIN, null, null)))
			.param("from", day.toString())
			.param("to", day.toString())).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void 직원은_보지_못한다() throws Exception {
		list(login(newAccount(), UserRole.STAFF, 1L, null), null).andExpect(status().isForbidden());
	}

	private ResultActions list(SecurityContext login, Long partnerId) throws Exception {
		var request = get(LIST).with(securityContext(login)).param("from", day.toString()).param("to", day.toString());
		if (partnerId != null) {
			request.param("partnerId", String.valueOf(partnerId));
		}
		return mockMvc.perform(request);
	}

	private PartnerRedemption redeem(long partnerId, String name, long fee, java.time.LocalDateTime at) {
		Instant redeemedAt = at.atZone(SEOUL).toInstant();
		return transaction.execute(status -> {
			Pass pass = passes.saveAndFlush(campusPass(passBenefits.current().campusId(), newStudentId(), name, "idx",
					redeemedAt.minusSeconds(3600), new BigDecimal("0.05")));
			PartnerRedemption redemption = pass.redeemAtPartner(partnerId,
					PartnerDiscount.of(Money.of(fee), pass.getDiscountRate()), redeemedAt);
			passes.saveAndFlush(pass);
			return partnerRedemptions.saveAndFlush(redemption);
		});
	}

}
