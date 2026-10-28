package com.jey.dongtanpass.web;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.jey.TestCsrf.csrfToken;
import static com.jey.TestIdempotency.idempotencyKey;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriUtils;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.jey.SheetsTestSupport;
import com.jey.WithJeyUser;
import com.jey.core.auth.api.UserRole;
import com.jey.dongtanpass.DongtanpassFixtures;
import com.jey.dongtanpass.PartnerLedgerFixtures;
import com.jey.dongtanpass.application.PartnerLedgerService;
import com.jey.dongtanpass.domain.PartnerLedgerKind;
import com.jey.dongtanpass.domain.PartnerLedgerSync;
import com.jey.dongtanpass.domain.PartnerLedgerSyncRepository;
import com.jey.dongtanpass.domain.PartnerRedemptionRepository;
import com.jey.dongtanpass.domain.PassRepository;
import com.jey.dongtanpass.domain.RedemptionRepository;
import tools.jackson.databind.json.JsonMapper;

// 연계 학원 장부 미반영 목록과 다시 반영. 관리자 전용이다. 전화번호는 싣지 않는다.
class PartnerLedgerSyncApiTest extends SheetsTestSupport {

	private static final String BASE = "/api/v1/dongtanpass/partner-ledger-sync";

	private static final String UNSYNCED = BASE + "/unsynced";

	private static final String TAB_PATH = "/v4/spreadsheets/" + PARTNER_SHEET_ID + "/values/'"
			+ UriUtils.encodePath("제이G패스", StandardCharsets.UTF_8) + "'";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	MockMvc mockMvc;

	@Autowired
	PartnerLedgerService ledger;

	@Autowired
	PartnerLedgerSyncRepository syncs;

	@Autowired
	PassRepository passes;

	@Autowired
	PartnerRedemptionRepository partnerRedemptions;

	@Autowired
	RedemptionRepository redemptions;

	@Autowired
	TransactionTemplate transaction;

	@Autowired
	JdbcTemplate jdbc;

	private PartnerLedgerFixtures fixtures;

	@BeforeEach
	void setUp() {
		DongtanpassFixtures.upsertPartner(jdbc, PARTNER_WITH_LEDGER, true);
		DongtanpassFixtures.upsertPartner(jdbc, PARTNER_WITHOUT_LEDGER, true);
		fixtures = new PartnerLedgerFixtures(passes, partnerRedemptions, redemptions, transaction);
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 관리자는_미반영_목록을_최근_것부터_보고_학원_이름과_학생_이름이_있고_번호는_없다() throws Exception {
		PartnerLedgerSync sync = unsynced(PARTNER_WITHOUT_LEDGER, "김하윤");

		mockMvc.perform(get(UNSYNCED).param("partnerId", String.valueOf(PARTNER_WITHOUT_LEDGER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].id").value(sync.getId()))
			.andExpect(jsonPath("$.items[0].passId").value(sync.getPassId()))
			.andExpect(jsonPath("$.items[0].partnerId").value(PARTNER_WITHOUT_LEDGER))
			.andExpect(jsonPath("$.items[0].partnerName").value("테스트학원-" + PARTNER_WITHOUT_LEDGER))
			.andExpect(jsonPath("$.items[0].kind").value("PARTNER_DISCOUNT"))
			.andExpect(jsonPath("$.items[0].studentName").value("김하윤"))
			.andExpect(jsonPath("$.items[0].reason").value("NOT_CONFIGURED"))
			.andExpect(jsonPath("$.items[0].attempts").value(1))
			.andExpect(jsonPath("$.items[0].lastAttemptAt").isNotEmpty())
			.andExpect(jsonPath("$.items[0].phone").doesNotExist())
			.andExpect(jsonPath("$.items[*].partnerId",
					Matchers.everyItem(Matchers.is((int) PARTNER_WITHOUT_LEDGER))))
			.andExpect(jsonPath("$.page").value(0))
			.andExpect(jsonPath("$.totalElements").isNumber());
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 학원을_고르지_않으면_전_학원이다() throws Exception {
		PartnerLedgerSync sync = unsynced(PARTNER_WITHOUT_LEDGER, "김하윤");

		mockMvc.perform(get(UNSYNCED).param("size", "100"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].id", Matchers.hasItem((int) (long) sync.getId())));
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 장부를_고친_뒤_다시_반영하면_바로_써_보고_결과_상태를_준다() throws Exception {
		PartnerLedgerSync sync = unsynced(PARTNER_WITH_LEDGER, "김하윤");
		stubRead(List.of(headerRow()));
		google.stubFor(WireMock.post(urlPathEqualTo(TAB_PATH + "!A:J:append"))
			.willReturn(okJson("{\"updates\": {\"updatedRange\": \"'제이G패스'!A2:J2\"}}")));

		mockMvc.perform(post(BASE + "/" + sync.getId() + "/retry").with(csrfToken(mockMvc)).with(idempotencyKey()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(sync.getId()))
			.andExpect(jsonPath("$.status").value("SYNCED"))
			.andExpect(jsonPath("$.reason").doesNotExist());
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 다시_해도_안_되면_미반영과_이유를_준다() throws Exception {
		PartnerLedgerSync sync = unsynced(PARTNER_WITH_LEDGER, "김하윤");
		stubRead(List.of(List.of("다른", "머리글")));

		mockMvc.perform(post(BASE + "/" + sync.getId() + "/retry").with(csrfToken(mockMvc)).with(idempotencyKey()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UNSYNCED"))
			.andExpect(jsonPath("$.reason").value("HEADER_MISMATCH"));
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 미반영이_아니면_409이고_없으면_404다() throws Exception {
		stubRead(List.of(headerRow()));
		google.stubFor(WireMock.post(urlPathEqualTo(TAB_PATH + "!A:J:append"))
			.willReturn(okJson("{\"updates\": {\"updatedRange\": \"'제이G패스'!A2:J2\"}}")));
		long passId = fixtures.campusPassRedeemedAt(PARTNER_WITH_LEDGER, "김하윤");
		ledger.record(passId, PARTNER_WITH_LEDGER, PartnerLedgerKind.PARTNER_DISCOUNT);
		long id = syncs.findByPassId(passId).orElseThrow().getId();

		mockMvc.perform(post(BASE + "/" + id + "/retry").with(csrfToken(mockMvc)).with(idempotencyKey()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PARTNER_LEDGER_SYNC_NOT_RETRYABLE"));
		mockMvc.perform(post(BASE + "/999999999/retry").with(csrfToken(mockMvc)).with(idempotencyKey()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PARTNER_LEDGER_SYNC_NOT_FOUND"));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1)
	void 직원은_목록도_다시_반영도_403이다() throws Exception {
		mockMvc.perform(get(UNSYNCED)).andExpect(status().isForbidden());
		mockMvc.perform(post(BASE + "/1/retry").with(csrfToken(mockMvc)).with(idempotencyKey()))
			.andExpect(status().isForbidden());
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = PARTNER_WITH_LEDGER)
	void 연계_학원_계정도_목록과_다시_반영이_403이다() throws Exception {
		mockMvc.perform(get(UNSYNCED)).andExpect(status().isForbidden());
		mockMvc.perform(post(BASE + "/1/retry").with(csrfToken(mockMvc)).with(idempotencyKey()))
			.andExpect(status().isForbidden());
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 페이지_번호가_한도를_넘으면_400이다() throws Exception {
		mockMvc.perform(get(UNSYNCED).param("page", "1000000000")).andExpect(status().isBadRequest());
		mockMvc.perform(get(UNSYNCED).param("size", "101")).andExpect(status().isBadRequest());
	}

	// 장부를 정하지 않은 학원이면 NOT_CONFIGURED, 정한 학원이면 이 시험의 처음 응답(흉내 낸 서버가 비어 404)으로 미반영이 된다.
	private PartnerLedgerSync unsynced(long partnerId, String studentName) {
		long passId = fixtures.campusPassRedeemedAt(partnerId, studentName);
		ledger.record(passId, partnerId, PartnerLedgerKind.PARTNER_DISCOUNT);
		return syncs.findByPassId(passId).orElseThrow();
	}

	private static List<String> headerRow() {
		return List.of("구분", "사용일", "학생", "패스 번호", "원래 수강료", "할인율", "할인액", "받을 금액", "JEY 지점", "앱 표시");
	}

	private static void stubRead(List<List<String>> values) {
		google.stubFor(WireMock.get(urlPathEqualTo(TAB_PATH + "!A:AZ"))
			.willReturn(okJson(JSON.writeValueAsString(Map.of("values", values)))));
	}

}
