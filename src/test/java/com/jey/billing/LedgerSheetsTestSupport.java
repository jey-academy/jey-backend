package com.jey.billing;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.util.UriUtils;

import com.jey.SheetsTestSupport;
import tools.jackson.databind.json.JsonMapper;

/**
 * 장부 연결을 켠 수납 장부 시험의 바탕. 동탄관(지점 1)의 장부가 흉내 낸 구글 서버의 {@value #SHEET_ID}다.
 * 스프링 테스트 설정과 흉내 낸 서버는 {@link SheetsTestSupport}에 있다(연계 학원 장부 시험과 컨텍스트 하나를 같이 쓴다).
 */
public abstract class LedgerSheetsTestSupport extends SheetsTestSupport {

	public static final String SHEET_ID = BILLING_SHEET_ID;

	/** 시험 지점(1, 동탄관)의 2026년 11월 탭. 탭 이름의 기본 틀(실제 장부 모양)로 만든 이름이다. */
	protected static final String TAB = "11월 동탄관(26)";

	/** 11월 탭을 읽는 경로. 흉내 낸 서버는 인코딩된 경로로 비교한다(한글과 공백). */
	protected static final String READ_PATH = "/v4/spreadsheets/" + SHEET_ID + "/values/'"
			+ UriUtils.encodePath(TAB, StandardCharsets.UTF_8) + "'!A:AZ";

	protected static final String WRITE_PATH = "/v4/spreadsheets/" + SHEET_ID + "/values:batchUpdate";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** 장부 잠금의 Redis 키(시험용 장부). */
	protected static final String LOCK_KEY = "jey:billing:ledger-lock:" + SHEET_ID;

	@Autowired
	protected StringRedisTemplate redis;

	@BeforeEach
	void resetLedgerLock() {
		expireLedgerLock();
	}

	/** 장부 잠금이 시간이 지나 풀린 것으로 한다(결과를 모르는 쓰기 뒤에는 잠금을 TTL까지 남겨 둔다). */
	protected void expireLedgerLock() {
		redis.delete(LOCK_KEY);
	}

	protected static String newClassName() {
		return "L" + UUID.randomUUID().toString().substring(0, 6);
	}

	/**
	 * 시안 모양의 11월 탭: 3행 머리글(P열 비고), 4행 보조 머리글, 5행부터 그 반 블록(5행은 사람이 쓴 줄, 6행부터 {@code blockRows}),
	 * 그 아래에 다른 반 블록.
	 */
	protected static List<List<String>> tab(String className, List<List<String>> blockRows) {
		List<List<String>> values = new ArrayList<>();
		values.add(List.of("11월 수강료 납부 일지 [동탄관]"));
		values.add(List.of());
		values.add(List.of("학년", "No", "학교", "성명", "부모 전화", "납입금액", "결제 내용", "매월 납입금액\n(8회기준)", "8월", "9월",
				"10월", "11월", "수강료", "교재비", "이번달 납부 내용", "비고"));
		values.add(List.of("", "", "", "", "", "", "", "", "납부일", "납부일", "납부일", "납부일"));
		values.add(List.of(className + "\nBH/SH", "1", "동탄초5", "김김김(" + className + ")", "(010) 0000-0101"));
		values.addAll(blockRows);
		values.add(List.of("Z9\nBH", "1"));
		return values;
	}

	/** 사람이 쓴 줄(성명만). */
	protected static List<String> filled(String name, String className) {
		return List.of("", "", "동탄초5", name + "(" + className + ")");
	}

	/** 앱이 쓴 줄(성명과 비고의 표시). */
	protected static List<String> written(String name, String className, String marker) {
		return List.of("", "", "동탄고1", name + "(" + className + ")", "", "", "", "", "", "", "", "", "", "", "", marker);
	}

	/** No만 있는 빈 줄. */
	protected static List<String> empty() {
		return List.of("", "-");
	}

	protected static String json(List<List<String>> values) {
		return JSON.writeValueAsString(Map.of("values", values));
	}

	/** 6행은 사람이 쓴 줄, 7~9행은 빈 줄. */
	protected static void stubTab(String className) {
		stubRead(tab(className, List.of(filled("박박박", className), empty(), empty(), empty())));
	}

	protected static void stubRead(List<List<String>> values) {
		google.stubFor(get(urlPathEqualTo(READ_PATH)).willReturn(okJson(json(values))));
	}

	protected static void stubWriteOk() {
		google.stubFor(post(urlPathEqualTo(WRITE_PATH)).willReturn(okJson("{\"totalUpdatedCells\": 1}")));
	}

}
