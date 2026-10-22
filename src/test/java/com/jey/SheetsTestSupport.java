package com.jey;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;

/**
 * 구글 시트 연결을 켠 스프링 테스트의 바탕. 수납 장부와 연계 학원 장부의 시험이 이것을 이어받아 컨텍스트 하나를 같이 쓴다
 * (설정이 하나라도 다르면 컨텍스트가 하나 더 뜬다 — 그래서 두 모듈의 장부 설정을 여기 모은다).
 *
 * <ul>
 * <li>흉내 낸 구글 서버는 테스트 실행 동안 하나만 띄워(주소가 같아야 컨텍스트를 다시 쓴다) 테스트마다 응답을 비운다.
 * <li>수납 장부: 지점 1(동탄관)의 장부가 {@value #BILLING_SHEET_ID}다.
 * <li>연계 학원 장부: 학원 {@value #PARTNER_WITH_LEDGER}의 장부가 {@value #PARTNER_SHEET_ID}다. 학원 {@value #PARTNER_WITHOUT_LEDGER}는 장부가 없다.
 * <li>다시 시도 스케줄러는 돌지 않게 간격을 길게 잡는다(테스트가 직접 부른다). 시간 제한은 1초로 줄인다(장애 시험이 빨리 끝나게).
 * </ul>
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class SheetsTestSupport {

	public static final String BILLING_SHEET_ID = "sheet-1";

	public static final String PARTNER_SHEET_ID = "partner-sheet-1";

	/** 장부가 있는 연계 학원. 테스트가 이 ID로 학원 행을 넣는다({@code DongtanpassFixtures}의 902_0xx 대역). */
	public static final long PARTNER_WITH_LEDGER = 902_001L;

	/** 장부를 정하지 않은 연계 학원. */
	public static final long PARTNER_WITHOUT_LEDGER = 902_002L;

	protected static final WireMockServer google = start();

	private static WireMockServer start() {
		WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort());
		server.start();
		Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
		return server;
	}

	@DynamicPropertySource
	static void sheets(DynamicPropertyRegistry registry) {
		registry.add("jey.sheets.base-url", google::baseUrl);
		registry.add("jey.sheets.service-account-key", TestServiceAccountKeys::base64);
		registry.add("jey.sheets.connect-timeout", () -> "1s");
		registry.add("jey.sheets.read-timeout", () -> "1s");
		registry.add("jey.billing.ledger.spreadsheets.1", () -> BILLING_SHEET_ID);
		registry.add("jey.billing.ledger.retry-interval", () -> "1h");
		registry.add("jey.dongtanpass.partner-ledger.spreadsheets." + PARTNER_WITH_LEDGER, () -> PARTNER_SHEET_ID);
		registry.add("jey.dongtanpass.partner-ledger.retry-interval", () -> "1h");
	}

	@BeforeEach
	void resetGoogle() {
		google.resetAll();
	}

}
