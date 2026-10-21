package com.jey.billing.infrastructure;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.jey.billing.application.LedgerSheetsRejected;
import com.jey.billing.application.LedgerSheetsUnavailable;
import com.jey.billing.domain.LedgerSyncReason;

// 구글 시트 값 API를 흉내 낸 서버로 읽기·쓰기 요청의 모양과, 응답·장애가 어느 결과로 바뀌는지 본다. Docker가 필요 없다.
class GoogleSheetsLedgerSheetsTest {

	@RegisterExtension
	static WireMockExtension google = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

	private static final String SHEET_ID = "sheet-1";

	private static final String READ_PATH = "/v4/spreadsheets/sheet-1/values/'2026-11'!A:AZ";

	private static final String WRITE_PATH = "/v4/spreadsheets/sheet-1/values:batchUpdate";

	private final GoogleSheetsLedgerSheets sheets = new GoogleSheetsLedgerSheets(google.baseUrl(),
			Duration.ofSeconds(1), Duration.ofMillis(500), () -> "Bearer test-token");

	@Test
	void 탭의_값을_보이는_글자_그대로_줄마다_읽는다() {
		google.stubFor(get(urlPathEqualTo(READ_PATH)).willReturn(okJson("""
				{"range": "'2026-11'!A1:Q5", "majorDimension": "ROWS",
				 "values": [["11월 수강료 납부 일지 [동탄관]"], [], ["학년", "No", "학교"], ["EJ5\\nBH/SH", "1", "동탄초5", "299,250 "]]}""")));

		List<List<String>> values = sheets.read(SHEET_ID, "2026-11");

		assertThat(values).containsExactly(List.of("11월 수강료 납부 일지 [동탄관]"), List.of(),
				List.of("학년", "No", "학교"), List.of("EJ5\nBH/SH", "1", "동탄초5", "299,250 "));
		google.verify(getRequestedFor(urlPathEqualTo(READ_PATH))
			.withQueryParam("valueRenderOption", equalTo("FORMATTED_VALUE"))
			.withQueryParam("majorDimension", equalTo("ROWS"))
			.withHeader("Authorization", equalTo("Bearer test-token")));
	}

	@Test
	void 빈_탭은_빈_목록이다() {
		google.stubFor(get(urlPathEqualTo(READ_PATH)).willReturn(okJson("""
				{"range": "'2026-11'!A1:AZ1000", "majorDimension": "ROWS"}""")));

		assertThat(sheets.read(SHEET_ID, "2026-11")).isEmpty();
	}

	@Test
	void 탭_이름의_작은따옴표는_두_번_써서_감싼다() {
		google.stubFor(get(urlPathEqualTo("/v4/spreadsheets/sheet-1/values/'a''b'!A:AZ"))
			.willReturn(okJson("{\"values\": [[\"x\"]]}")));

		assertThat(sheets.read(SHEET_ID, "a'b")).containsExactly(List.of("x"));
	}

	@Test
	void 탭이_없으면_탭_없음으로_거절된다() {
		google.stubFor(get(urlPathEqualTo(READ_PATH)).willReturn(aResponse().withStatus(400)
			.withHeader("Content-Type", "application/json")
			.withBody("""
					{"error": {"code": 400, "message": "Unable to parse range: '2026-11'!A:AZ", "status": "INVALID_ARGUMENT"}}""")));

		assertRejected(() -> sheets.read(SHEET_ID, "2026-11"), LedgerSyncReason.TAB_NOT_FOUND);
	}

	@Test
	void 범위_말고_다른_이유의_400은_구글이_요청을_거절한_것이다() {
		google.stubFor(get(urlPathEqualTo(READ_PATH)).willReturn(aResponse().withStatus(400)
			.withHeader("Content-Type", "application/json")
			.withBody("{\"error\": {\"code\": 400, \"message\": \"Invalid value\", \"status\": \"INVALID_ARGUMENT\"}}")));

		assertRejected(() -> sheets.read(SHEET_ID, "2026-11"), LedgerSyncReason.GOOGLE_REJECTED);
	}

	@Test
	void 권한이_없거나_인증이_틀리면_권한_없음이다() {
		google.stubFor(get(urlPathEqualTo(READ_PATH)).willReturn(aResponse().withStatus(403)));
		assertRejected(() -> sheets.read(SHEET_ID, "2026-11"), LedgerSyncReason.ACCESS_DENIED);

		google.stubFor(get(urlPathEqualTo(READ_PATH)).willReturn(aResponse().withStatus(401)));
		assertRejected(() -> sheets.read(SHEET_ID, "2026-11"), LedgerSyncReason.ACCESS_DENIED);
	}

	@Test
	void 스프레드시트가_없으면_설정이_틀린_것이다() {
		google.stubFor(get(urlPathEqualTo(READ_PATH)).willReturn(aResponse().withStatus(404)));

		assertRejected(() -> sheets.read(SHEET_ID, "2026-11"), LedgerSyncReason.NOT_CONFIGURED);
	}

	@Test
	void 구글의_5xx는_장애다() {
		google.stubFor(get(urlPathEqualTo(READ_PATH)).willReturn(aResponse().withStatus(503)));

		assertThatThrownBy(() -> sheets.read(SHEET_ID, "2026-11"))
			.isInstanceOfSatisfying(LedgerSheetsUnavailable.class, e -> assertThat(e.retryAfter()).isNull());
	}

	@Test
	void 요청_한도에_걸리면_장애이고_기다릴_시간을_함께_전한다() {
		google.stubFor(get(urlPathEqualTo(READ_PATH))
			.willReturn(aResponse().withStatus(429).withHeader("Retry-After", "120")));

		assertThatThrownBy(() -> sheets.read(SHEET_ID, "2026-11")).isInstanceOfSatisfying(
				LedgerSheetsUnavailable.class, e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(120)));
	}

	@Test
	void 기다릴_시간을_숫자로_주지_않으면_없는_것으로_본다() {
		google.stubFor(get(urlPathEqualTo(READ_PATH)).willReturn(
				aResponse().withStatus(429).withHeader("Retry-After", "Wed, 21 Oct 2026 07:28:00 GMT")));

		assertThatThrownBy(() -> sheets.read(SHEET_ID, "2026-11"))
			.isInstanceOfSatisfying(LedgerSheetsUnavailable.class, e -> assertThat(e.retryAfter()).isNull());
	}

	@Test
	void 응답이_읽기_시간_제한보다_늦으면_장애다() {
		google.stubFor(get(urlPathEqualTo(READ_PATH))
			.willReturn(okJson("{\"values\": []}").withFixedDelay(2_000)));

		long started = System.nanoTime();
		assertThatThrownBy(() -> sheets.read(SHEET_ID, "2026-11")).isInstanceOf(LedgerSheetsUnavailable.class);
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1_800));
	}

	@Test
	void 연결이_끊기면_장애다() {
		google.stubFor(get(urlPathEqualTo(READ_PATH))
			.willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

		assertThatThrownBy(() -> sheets.read(SHEET_ID, "2026-11")).isInstanceOf(LedgerSheetsUnavailable.class);
	}

	// 응답 머리글은 받았는데 본문을 읽다 끊겼다. 다시 하면 나을 수 있는 장애다(앱 오류가 아니다).
	@Test
	void 응답_본문을_읽다_끊기면_장애다() {
		google.stubFor(get(urlPathEqualTo(READ_PATH)).willReturn(aResponse().withFault(Fault.MALFORMED_RESPONSE_CHUNK)));

		assertThatThrownBy(() -> sheets.read(SHEET_ID, "2026-11")).isInstanceOf(LedgerSheetsUnavailable.class);
	}

	@Test
	void 연결할_수_없으면_장애다() {
		GoogleSheetsLedgerSheets nowhere = new GoogleSheetsLedgerSheets("http://127.0.0.1:1", Duration.ofSeconds(1),
				Duration.ofMillis(500), () -> "Bearer test-token");

		assertThatThrownBy(() -> nowhere.read(SHEET_ID, "2026-11")).isInstanceOf(LedgerSheetsUnavailable.class);
	}

	@Test
	void 쓸_때는_칸마다_범위_하나로_입력한_그대로_쓴다() {
		google.stubFor(post(urlPathEqualTo(WRITE_PATH)).willReturn(okJson("{\"totalUpdatedCells\": 3}")));
		Map<String, Object> cells = new LinkedHashMap<>();
		cells.put("D10", "김하윤(EJ5)");
		cells.put("F10", 299_250L);
		cells.put("L10", "10/31");

		sheets.write(SHEET_ID, "2026-11", cells);

		google.verify(postRequestedFor(urlPathEqualTo(WRITE_PATH))
			.withHeader("Authorization", equalTo("Bearer test-token"))
			.withRequestBody(equalToJson("""
					{"valueInputOption": "RAW",
					 "data": [
					   {"range": "'2026-11'!D10", "values": [["김하윤(EJ5)"]]},
					   {"range": "'2026-11'!F10", "values": [[299250]]},
					   {"range": "'2026-11'!L10", "values": [["10/31"]]}
					 ]}""")));
	}

	@Test
	void 쓰기의_장애와_거절도_읽기와_같이_바뀐다() {
		google.stubFor(post(urlPathEqualTo(WRITE_PATH)).willReturn(aResponse().withStatus(500)));
		assertThatThrownBy(() -> sheets.write(SHEET_ID, "2026-11", Map.of("D10", "x")))
			.isInstanceOf(LedgerSheetsUnavailable.class);

		google.stubFor(post(urlPathEqualTo(WRITE_PATH)).willReturn(aResponse().withStatus(403)));
		assertRejected(() -> sheets.write(SHEET_ID, "2026-11", Map.of("D10", "x")), LedgerSyncReason.ACCESS_DENIED);
	}

	@Test
	void 예외_문구에는_시트의_값이나_응답_본문이_실리지_않는다() {
		google.stubFor(get(urlPathEqualTo(READ_PATH)).willReturn(aResponse().withStatus(400)
			.withHeader("Content-Type", "application/json")
			.withBody("{\"error\": {\"code\": 400, \"message\": \"010-1234-5678 김하윤\"}}")));

		assertThatThrownBy(() -> sheets.read(SHEET_ID, "2026-11"))
			.satisfies(e -> assertThat(e.getMessage()).doesNotContain("1234").doesNotContain("김하윤"));
	}

	private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
			LedgerSyncReason reason) {
		assertThatThrownBy(call).isInstanceOfSatisfying(LedgerSheetsRejected.class,
				e -> assertThat(e.reason()).isEqualTo(reason));
	}

}
