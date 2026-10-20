package com.jey.billing;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jey.billing.infrastructure.TestServiceAccountKeys;

/**
 * 장부 연결을 켠 스프링 테스트의 바탕. 동탄관(지점 1)의 장부가 흉내 낸 구글 서버의 {@value #SHEET_ID}다.
 * 흉내 낸 서버는 테스트 실행 동안 하나만 띄워(주소가 같아야 스프링 컨텍스트를 다시 쓴다) 테스트마다 응답을 비운다.
 * 다시 시도 스케줄러는 돌지 않게 간격을 길게 잡는다(테스트가 직접 부른다).
 */
public abstract class LedgerSheetsTestSupport {

	public static final String SHEET_ID = "sheet-1";

	protected static final WireMockServer google = start();

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
	}

	@BeforeEach
	void resetGoogle() {
		google.resetAll();
	}

}
