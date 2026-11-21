package com.jey.dongtanpass.web;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;

import com.jey.SheetsTestSupport;
import com.jey.WithJeyUser;
import com.jey.core.auth.api.UserRole;
import com.jey.dongtanpass.PartnerLedgerFixtures;
import com.jey.dongtanpass.domain.PartnerRedemptionRepository;
import com.jey.dongtanpass.domain.PassRepository;
import com.jey.dongtanpass.domain.RedemptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import static com.jey.dongtanpass.DongtanpassFixtures.upsertPartner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 연계 학원은 시트 장부와 같은 칸의 장부를 CSV로 내려받는다. 시트를 쓰든 안 쓰든 같다.
// 기록은 2032년에 넣고 그 기간으로 받는다(다른 테스트의 기록과 섞이지 않게). 지점 1은 동탄관이다.
class PartnerLedgerExportApiTest extends SheetsTestSupport {

	private static final String EXPORT = "/api/v1/dongtanpass/partner-ledger/export";

	private static final long PARTNER = 902_602L;

	private static final long OTHER = 902_603L;

	private static final long INACTIVE = 902_604L;

	/** 한국 시각 2032-03-10 12:00. */
	private static final Instant USED_AT = Instant.parse("2032-03-10T03:00:00Z");

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PassRepository passes;

	@Autowired
	PartnerRedemptionRepository partnerRedemptions;

	@Autowired
	RedemptionRepository redemptions;

	@Autowired
	TransactionTemplate transaction;

	private PartnerLedgerFixtures fixtures;

	@BeforeEach
	void setUp() {
		fixtures = new PartnerLedgerFixtures(passes, partnerRedemptions, redemptions, transaction);
		upsertPartner(jdbc, PARTNER, true);
		upsertPartner(jdbc, OTHER, true);
		upsertPartner(jdbc, INACTIVE, false);
		upsertPartner(jdbc, PARTNER_WITH_LEDGER, true);
		jdbc.update("DELETE FROM dongtanpass_partner_redemption WHERE redeemed_at >= '2032-01-01'");
		jdbc.update("DELETE FROM dongtanpass_redemption WHERE redeemed_at >= '2032-01-01'");
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = PARTNER)
	void 할인해_준_기록과_JEY에서_쓰인_기록을_최신_사용_순으로_내려준다() throws Exception {
		long earlier = fixtures.campusPassRedeemedAt(PARTNER, "박이전", USED_AT.minusSeconds(86_400));
		long discount = fixtures.campusPassRedeemedAt(PARTNER, "김하윤", USED_AT);
		long usedAtJey = fixtures.partnerPassRedeemedAtDesk(PARTNER, "이서준", USED_AT);

		byte[] file = mockMvc.perform(get(EXPORT).param("from", "2032-03-01").param("to", "2032-03-31"))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Type", startsWith("text/csv")))
			.andExpect(header().string("Content-Disposition", containsString("attachment")))
			.andExpect(header().string("Content-Disposition", containsString("dongtanpass-partner-ledger.csv")))
			.andReturn()
			.getResponse()
			.getContentAsByteArray();

		assertThat(Arrays.copyOf(file, 3)).containsExactly(0xEF, 0xBB, 0xBF);
		String[] lines = new String(file, 3, file.length - 3, StandardCharsets.UTF_8).split("\r\n");
		// 같은 시각이면 패스 ID가 큰 것(나중에 만든 것)이 먼저다.
		assertThat(lines).containsExactly(
				"\"구분\",\"사용일\",\"학생\",\"패스 번호\",\"원래 수강료\",\"할인율\",\"할인액\",\"받을 금액\",\"JEY 지점\"",
				"\"JEY에서 사용\",\"2032-03-10\",\"이서준\",\"" + passNo(usedAtJey) + "\",\"\",\"\",\"\",\"\",\"동탄관\"",
				"\"제이G패스 할인\",\"2032-03-10\",\"김하윤\",\"" + passNo(discount)
						+ "\",\"280000\",\"5%\",\"14000\",\"266000\",\"동탄관\"",
				"\"제이G패스 할인\",\"2032-03-09\",\"박이전\",\"" + passNo(earlier)
						+ "\",\"280000\",\"5%\",\"14000\",\"266000\",\"동탄관\"");
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = PARTNER)
	void 다른_학원의_기록은_나오지_않는다() throws Exception {
		fixtures.campusPassRedeemedAt(OTHER, "박다른", USED_AT);
		fixtures.partnerPassRedeemedAtDesk(OTHER, "최다른", USED_AT);
		fixtures.campusPassRedeemedAt(PARTNER, "김하윤", USED_AT);

		assertThat(csv("2032-03-01", "2032-03-31")).contains("\"김하윤\"")
			.doesNotContain("\"박다른\"")
			.doesNotContain("\"최다른\"");
	}

	// 한국 날짜로 자른다: 4/1 0시부터 4/30 23:59:59까지.
	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = PARTNER)
	void 사용일로_자르고_양끝을_포함한다() throws Exception {
		fixtures.campusPassRedeemedAt(PARTNER, "전날밤", Instant.parse("2032-03-31T14:59:59Z"));
		fixtures.campusPassRedeemedAt(PARTNER, "첫날0시", Instant.parse("2032-03-31T15:00:00Z"));
		fixtures.partnerPassRedeemedAtDesk(PARTNER, "끝날밤", Instant.parse("2032-04-30T14:59:59Z"));
		fixtures.partnerPassRedeemedAtDesk(PARTNER, "다음날0시", Instant.parse("2032-04-30T15:00:00Z"));

		assertThat(csv("2032-04-01", "2032-04-30")).contains("\"첫날0시\",", "\"끝날밤\",")
			.doesNotContain("\"전날밤\"")
			.doesNotContain("\"다음날0시\"");
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = PARTNER_WITH_LEDGER)
	void 시트를_쓰는_학원도_내려받을_수_있다() throws Exception {
		fixtures.campusPassRedeemedAt(PARTNER_WITH_LEDGER, "김하윤", USED_AT);

		assertThat(csv("2032-03-01", "2032-03-31")).contains("\"제이G패스 할인\",\"2032-03-10\",\"김하윤\"");
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = PARTNER)
	void 수식으로_시작하는_이름은_글자로_나간다() throws Exception {
		fixtures.campusPassRedeemedAt(PARTNER, "=1+1", USED_AT);

		assertThat(csv("2032-03-01", "2032-03-31")).contains("\"'=1+1\"");
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = PARTNER)
	void 시작일이_종료일보다_뒤면_400이다() throws Exception {
		mockMvc.perform(get(EXPORT).param("from", "2032-03-02").param("to", "2032-03-01"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_INVALID_PERIOD"));
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = PARTNER)
	void 기간이_1년을_넘으면_400이다() throws Exception {
		mockMvc.perform(get(EXPORT).param("from", "2032-01-01").param("to", "2033-01-02"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PERIOD_TOO_LONG"));
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = INACTIVE)
	void 꺼진_학원은_403이다() throws Exception {
		mockMvc.perform(get(EXPORT).param("from", "2032-03-01").param("to", "2032-03-31"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PARTNER_INACTIVE"));
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 관리자는_403이다() throws Exception {
		mockMvc.perform(get(EXPORT).param("from", "2032-03-01").param("to", "2032-03-31"))
			.andExpect(status().isForbidden());
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1)
	void 직원은_403이다() throws Exception {
		mockMvc.perform(get(EXPORT).param("from", "2032-03-01").param("to", "2032-03-31"))
			.andExpect(status().isForbidden());
	}

	private String csv(String from, String to) throws Exception {
		byte[] file = mockMvc.perform(get(EXPORT).param("from", from).param("to", to))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsByteArray();
		return new String(file, 3, file.length - 3, StandardCharsets.UTF_8);
	}

	private String passNo(long passId) {
		return passes.findById(passId).orElseThrow().getPassNo();
	}

}
