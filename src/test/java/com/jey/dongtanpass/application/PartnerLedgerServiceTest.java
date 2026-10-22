package com.jey.dongtanpass.application;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriUtils;

import com.jey.SheetsTestSupport;
import com.jey.core.shared.Actor;
import com.jey.core.shared.EventMetadata;
import com.jey.dongtanpass.DongtanpassFixtures;
import com.jey.dongtanpass.PartnerLedgerFixtures;
import com.jey.dongtanpass.api.PartnerPassRedeemed;
import com.jey.dongtanpass.api.PassRedeemed;
import com.jey.dongtanpass.domain.PartnerLedgerKind;
import com.jey.dongtanpass.domain.PartnerLedgerReason;
import com.jey.dongtanpass.domain.PartnerLedgerStatus;
import com.jey.dongtanpass.domain.PartnerLedgerSync;
import com.jey.dongtanpass.domain.PartnerLedgerSyncRepository;
import com.jey.dongtanpass.domain.PartnerRedemptionRepository;
import com.jey.dongtanpass.domain.PassRepository;
import com.jey.dongtanpass.domain.RedemptionRepository;
import tools.jackson.databind.json.JsonMapper;

// 패스 사용을 그 연계 학원의 장부(흉내 낸 구글 서버)에 한 줄씩 붙인다. 지점 1은 동탄관이다.
class PartnerLedgerServiceTest extends SheetsTestSupport {

	private static final String TAB_PATH = "/v4/spreadsheets/" + PARTNER_SHEET_ID + "/values/'"
			+ UriUtils.encodePath("JEY 패스", StandardCharsets.UTF_8) + "'";

	private static final String READ_PATH = TAB_PATH + "!A:AZ";

	private static final String APPEND_PATH = TAB_PATH + "!A:J:append";

	private static final JsonMapper JSON = JsonMapper.builder().build();

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
	ApplicationEventPublisher events;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Clock clock;

	private PartnerLedgerFixtures fixtures;

	@BeforeEach
	void partners() {
		fixtures = new PartnerLedgerFixtures(passes, partnerRedemptions, redemptions, transaction);
		DongtanpassFixtures.upsertPartner(jdbc, PARTNER_WITH_LEDGER, true);
		DongtanpassFixtures.upsertPartner(jdbc, PARTNER_WITHOUT_LEDGER, true);
	}

	@Test
	void 할인해_준_기록을_그_학원_장부에_붙인다() {
		stubRead(List.of(PartnerLedgerRow.HEADER));
		stubAppend(7);
		long passId = fixtures.campusPassRedeemedAt(PARTNER_WITH_LEDGER, "김하윤");

		ledger.record(passId, PARTNER_WITH_LEDGER, PartnerLedgerKind.PARTNER_DISCOUNT);

		google.verify(postRequestedFor(urlPathEqualTo(APPEND_PATH)).withRequestBody(equalToJson(JSON.writeValueAsString(
				Map.of("values", List.of(List.of("JEY 패스 할인", "2026-11-03", "김하윤", passNo(passId), 280_000, "5%",
						14_000, 266_000, "동탄관", "앱:패스-" + passId)))))));
		PartnerLedgerSync sync = syncs.findByPassId(passId).orElseThrow();
		assertThat(sync.getStatus()).isEqualTo(PartnerLedgerStatus.SYNCED);
		assertThat(sync.getSheetRow()).isEqualTo(7);
		assertThat(sync.getKind()).isEqualTo(PartnerLedgerKind.PARTNER_DISCOUNT);
	}

	@Test
	void 그_학원이_발급한_패스가_JEY에서_쓰인_기록을_붙인다() {
		stubRead(List.of(PartnerLedgerRow.HEADER));
		stubAppend(3);
		long passId = fixtures.partnerPassRedeemedAtDesk(PARTNER_WITH_LEDGER, "이서준");

		ledger.record(passId, PARTNER_WITH_LEDGER, PartnerLedgerKind.USED_AT_JEY);

		google.verify(postRequestedFor(urlPathEqualTo(APPEND_PATH)).withRequestBody(equalToJson(JSON.writeValueAsString(
				Map.of("values", List.of(List.of("JEY에서 사용", "2026-11-03", "이서준", passNo(passId), "", "", "", "",
						"동탄관", "앱:패스-" + passId)))))));
		assertThat(syncs.findByPassId(passId).orElseThrow().getStatus()).isEqualTo(PartnerLedgerStatus.SYNCED);
	}

	@Test
	void 같은_기록을_두_번_받아도_한_번만_붙인다() {
		stubRead(List.of(PartnerLedgerRow.HEADER));
		stubAppend(7);
		long passId = fixtures.campusPassRedeemedAt(PARTNER_WITH_LEDGER, "김하윤");

		ledger.record(passId, PARTNER_WITH_LEDGER, PartnerLedgerKind.PARTNER_DISCOUNT);
		ledger.record(passId, PARTNER_WITH_LEDGER, PartnerLedgerKind.PARTNER_DISCOUNT);

		google.verify(1, postRequestedFor(urlPathEqualTo(APPEND_PATH)));
	}

	@Test
	void 앞선_시도가_붙인_줄이_있으면_붙이지_않고_그_줄로_반영됨이다() {
		long passId = fixtures.campusPassRedeemedAt(PARTNER_WITH_LEDGER, "김하윤");
		stubRead(List.of(PartnerLedgerRow.HEADER, List.of("JEY 패스 할인", "2026-11-01"),
				List.of("JEY 패스 할인", "2026-11-03", "김하윤", "", "", "", "", "", "", "앱:패스-" + passId)));

		ledger.record(passId, PARTNER_WITH_LEDGER, PartnerLedgerKind.PARTNER_DISCOUNT);

		google.verify(0, postRequestedFor(urlPathEqualTo(APPEND_PATH)));
		PartnerLedgerSync sync = syncs.findByPassId(passId).orElseThrow();
		assertThat(sync.getStatus()).isEqualTo(PartnerLedgerStatus.SYNCED);
		assertThat(sync.getSheetRow()).isEqualTo(3);
	}

	@Test
	void 장부를_정하지_않은_학원은_구글을_부르지_않고_미반영이다() {
		long passId = fixtures.campusPassRedeemedAt(PARTNER_WITHOUT_LEDGER, "김하윤");

		ledger.record(passId, PARTNER_WITHOUT_LEDGER, PartnerLedgerKind.PARTNER_DISCOUNT);

		assertThat(google.getAllServeEvents()).isEmpty();
		assertUnsynced(passId, PartnerLedgerReason.NOT_CONFIGURED);
	}

	@Test
	void 머리글이_다르면_쓰지_않고_미반영이다() {
		stubRead(List.of(List.of("학년", "No", "학교", "성명")));
		long passId = fixtures.campusPassRedeemedAt(PARTNER_WITH_LEDGER, "김하윤");

		ledger.record(passId, PARTNER_WITH_LEDGER, PartnerLedgerKind.PARTNER_DISCOUNT);

		google.verify(0, postRequestedFor(urlPathEqualTo(APPEND_PATH)));
		assertUnsynced(passId, PartnerLedgerReason.HEADER_MISMATCH);
	}

	@Test
	void 빈_탭도_머리글이_다른_것이다() {
		stubRead(List.of());
		long passId = fixtures.campusPassRedeemedAt(PARTNER_WITH_LEDGER, "김하윤");

		ledger.record(passId, PARTNER_WITH_LEDGER, PartnerLedgerKind.PARTNER_DISCOUNT);

		assertUnsynced(passId, PartnerLedgerReason.HEADER_MISMATCH);
	}

	@Test
	void 탭이_없으면_미반영이다() {
		google.stubFor(get(urlPathEqualTo(READ_PATH)).willReturn(aResponse().withStatus(400)
			.withHeader("Content-Type", "application/json")
			.withBody("{\"error\": {\"code\": 400, \"message\": \"Unable to parse range: 'JEY 패스'!A:AZ\"}}")));
		long passId = fixtures.campusPassRedeemedAt(PARTNER_WITH_LEDGER, "김하윤");

		ledger.record(passId, PARTNER_WITH_LEDGER, PartnerLedgerKind.PARTNER_DISCOUNT);

		assertUnsynced(passId, PartnerLedgerReason.TAB_NOT_FOUND);
	}

	@Test
	void 구글_장애면_1분_뒤에_다시_하도록_기다린다() {
		google.stubFor(get(urlPathEqualTo(READ_PATH)).willReturn(aResponse().withStatus(503)));
		long passId = fixtures.campusPassRedeemedAt(PARTNER_WITH_LEDGER, "김하윤");
		Instant before = clock.instant();

		ledger.record(passId, PARTNER_WITH_LEDGER, PartnerLedgerKind.PARTNER_DISCOUNT);

		PartnerLedgerSync sync = syncs.findByPassId(passId).orElseThrow();
		assertThat(sync.getStatus()).isEqualTo(PartnerLedgerStatus.PENDING);
		assertThat(sync.getAttempts()).isEqualTo(1);
		assertThat(sync.getNextAttemptAt()).isAfterOrEqualTo(before.plus(Duration.ofMinutes(1)).minusSeconds(1));
	}

	// 붙이기가 시간 초과로 끝났지만 구글은 붙였다. 다음 시도는 시트에서 표시를 찾아 반영됨으로 끝내고 또 붙이지 않는다.
	@Test
	void 붙이기의_결과를_모르면_다음_시도가_표시를_찾아_두_번_붙이지_않는다() {
		stubRead(List.of(PartnerLedgerRow.HEADER));
		google.stubFor(post(urlPathEqualTo(APPEND_PATH)).willReturn(
				okJson("{\"updates\": {\"updatedRange\": \"'JEY 패스'!A2:J2\"}}").withFixedDelay(2_000)));
		long passId = fixtures.campusPassRedeemedAt(PARTNER_WITH_LEDGER, "김하윤");

		ledger.record(passId, PARTNER_WITH_LEDGER, PartnerLedgerKind.PARTNER_DISCOUNT);
		assertThat(syncs.findByPassId(passId).orElseThrow().getStatus()).isEqualTo(PartnerLedgerStatus.PENDING);

		stubRead(List.of(PartnerLedgerRow.HEADER,
				List.of("JEY 패스 할인", "2026-11-03", "김하윤", "", "", "", "", "", "", "앱:패스-" + passId)));
		makeDue(passId);
		ledger.retryDue();

		PartnerLedgerSync sync = syncs.findByPassId(passId).orElseThrow();
		assertThat(sync.getStatus()).isEqualTo(PartnerLedgerStatus.SYNCED);
		assertThat(sync.getSheetRow()).isEqualTo(2);
		google.verify(1, postRequestedFor(urlPathEqualTo(APPEND_PATH)));
	}

	@Test
	void 연계_학원의_사용_처리_이벤트를_받으면_그_학원_장부에_붙인다() {
		stubRead(List.of(PartnerLedgerRow.HEADER));
		stubAppend(5);
		long passId = fixtures.campusPassRedeemedAt(PARTNER_WITH_LEDGER, "김하윤");

		transaction.executeWithoutResult(status -> events.publishEvent(new PartnerPassRedeemed(EventMetadata
			.create(PartnerPassRedeemed.TYPE, PartnerPassRedeemed.VERSION, 1L, Actor.none(), clock), passId,
				PARTNER_WITH_LEDGER, PartnerLedgerFixtures.newStudentId())));

		awaitSynced(passId);
		assertThat(syncs.findByPassId(passId).orElseThrow().getKind()).isEqualTo(PartnerLedgerKind.PARTNER_DISCOUNT);
	}

	@Test
	void 데스크의_사용_처리_이벤트를_받으면_발급한_학원_장부에_붙인다() {
		stubRead(List.of(PartnerLedgerRow.HEADER));
		stubAppend(5);
		long passId = fixtures.partnerPassRedeemedAtDesk(PARTNER_WITH_LEDGER, "이서준");

		transaction.executeWithoutResult(status -> events.publishEvent(new PassRedeemed(
				EventMetadata.create(PassRedeemed.TYPE, PassRedeemed.VERSION, 1L, Actor.none(), clock), passId,
				PartnerLedgerFixtures.newStudentId(), PARTNER_WITH_LEDGER)));

		awaitSynced(passId);
		assertThat(syncs.findByPassId(passId).orElseThrow().getKind()).isEqualTo(PartnerLedgerKind.USED_AT_JEY);
	}

	private void awaitSynced(long passId) {
		await().atMost(Duration.ofSeconds(15)).until(() -> syncs.findByPassId(passId)
			.map(PartnerLedgerSync::getStatus)
			.orElse(null) == PartnerLedgerStatus.SYNCED);
	}

	private void assertUnsynced(long passId, PartnerLedgerReason reason) {
		PartnerLedgerSync sync = syncs.findByPassId(passId).orElseThrow();
		assertThat(sync.getStatus()).isEqualTo(PartnerLedgerStatus.UNSYNCED);
		assertThat(sync.getReason()).isEqualTo(reason);
	}

	// 다음 시도 시각을 지난 것으로 당긴다(간격을 기다리지 않게).
	private void makeDue(long passId) {
		jdbc.update("UPDATE dongtanpass_partner_ledger_sync SET next_attempt_at = ? WHERE pass_id = ?",
				java.sql.Timestamp.from(clock.instant().minusSeconds(1)), passId);
	}

	private static void stubRead(List<List<String>> values) {
		List<List<String>> rows = new ArrayList<>(values);
		google.stubFor(get(urlPathEqualTo(READ_PATH)).willReturn(okJson(JSON.writeValueAsString(Map.of("values", rows)))));
	}

	private static void stubAppend(int row) {
		google.stubFor(post(urlPathEqualTo(APPEND_PATH)).willReturn(
				okJson("{\"updates\": {\"updatedRange\": \"'JEY 패스'!A" + row + ":J" + row + "\"}}")));
	}

	private String passNo(long passId) {
		return passes.findById(passId).orElseThrow().getPassNo();
	}

}
