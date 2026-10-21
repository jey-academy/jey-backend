package com.jey.billing;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jey.TestcontainersConfiguration;
import com.jey.billing.infrastructure.TestServiceAccountKeys;
import tools.jackson.databind.json.JsonMapper;

/**
 * 장부 연결을 켠 스프링 테스트의 바탕. 동탄관(지점 1)의 장부가 흉내 낸 구글 서버의 {@value #SHEET_ID}다.
 * 흉내 낸 서버는 테스트 실행 동안 하나만 띄워(주소가 같아야 스프링 컨텍스트를 다시 쓴다) 테스트마다 응답을 비운다.
 * 다시 시도 스케줄러는 돌지 않게 간격을 길게 잡는다(테스트가 직접 부른다). 시간 제한은 1초로 줄인다(장애 시험이 빨리 끝나게).
 * 스프링 테스트 설정을 여기 모아 둔다 — 장부 시험 클래스들이 컨텍스트 하나를 같이 쓴다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class LedgerSheetsTestSupport {

	public static final String SHEET_ID = "sheet-1";

	/** 11월 탭을 읽는 경로. */
	protected static final String READ_PATH = "/v4/spreadsheets/" + SHEET_ID + "/values/'2026-11'!A:AZ";

	protected static final String WRITE_PATH = "/v4/spreadsheets/" + SHEET_ID + "/values:batchUpdate";

	protected static final WireMockServer google = start();

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static WireMockServer start() {
		WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort());
		server.start();
		Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
		return server;
	}

	@DynamicPropertySource
	static void ledger(DynamicPropertyRegistry registry) {
		registry.add("jey.billing.ledger.base-url", google::baseUrl);
		registry.add("jey.billing.ledger.spreadsheets.1", () -> SHEET_ID);
		registry.add("jey.billing.ledger.service-account-key", TestServiceAccountKeys::base64);
		registry.add("jey.billing.ledger.retry-interval", () -> "1h");
		registry.add("jey.billing.ledger.connect-timeout", () -> "1s");
		registry.add("jey.billing.ledger.read-timeout", () -> "1s");
	}

	/** 장부 잠금의 Redis 키(시험용 장부). */
	protected static final String LOCK_KEY = "jey:billing:ledger-lock:" + SHEET_ID;

	@Autowired
	protected StringRedisTemplate redis;

	@BeforeEach
	void resetGoogle() {
		google.resetAll();
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
