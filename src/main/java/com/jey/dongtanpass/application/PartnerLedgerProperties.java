package com.jey.dongtanpass.application;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 연계 학원 장부 설정({@code jey.dongtanpass.partner-ledger.*}, ADR-0048). 틀린 값이면 서버가 뜨지 않는다.
 * 구글 주소·시간 제한·서비스 계정 키는 구글 시트 연결의 설정({@code jey.sheets.*})이다.
 *
 * @param spreadsheets 연계 학원 ID → 스프레드시트 ID. 없는 학원은 장부 연결이 꺼진 것이다(그 학원의 기록은 미반영 {@code NOT_CONFIGURED}).
 *     운영은 환경 변수 {@code JEY_DONGTANPASS_PARTNERLEDGER_SPREADSHEETS_{학원 ID}}로 정한다
 * @param tabName 기록을 붙이는 탭의 이름. 앱은 탭을 만들지 않는다
 * @param retryInterval 다시 시도할 줄을 찾는 간격. 10초 이상(단위를 빠뜨린 값을 막는다)
 */
@ConfigurationProperties("jey.dongtanpass.partner-ledger")
public record PartnerLedgerProperties(Map<Long, String> spreadsheets, @DefaultValue("제이G패스") String tabName,
		@DefaultValue("1m") Duration retryInterval) {

	private static final Pattern SPREADSHEET_ID = Pattern.compile("[A-Za-z0-9_-]{1,200}");

	private static final Duration MIN_RETRY_INTERVAL = Duration.ofSeconds(10);

	private static final int MAX_TAB_NAME_LENGTH = 100;

	private static final String DEFAULT_TAB_NAME = "제이G패스";

	public PartnerLedgerProperties {
		spreadsheets = spreadsheets == null ? Map.of() : Map.copyOf(spreadsheets);
		for (String id : spreadsheets.values()) {
			if (!SPREADSHEET_ID.matcher(id).matches()) {
				throw new IllegalArgumentException(
						"jey.dongtanpass.partner-ledger.spreadsheets의 값은 스프레드시트 ID다(주소 전체가 아니라 /d/ 뒤의 부분)");
			}
		}
		tabName = tabName == null ? DEFAULT_TAB_NAME : tabName;
		if (tabName.isBlank() || tabName.length() > MAX_TAB_NAME_LENGTH) {
			throw new IllegalArgumentException("jey.dongtanpass.partner-ledger.tab-name은 1~100자다");
		}
		if (retryInterval == null || retryInterval.compareTo(MIN_RETRY_INTERVAL) < 0) {
			throw new IllegalArgumentException(
					"jey.dongtanpass.partner-ledger.retry-interval은 10초 이상이다(단위를 적었는지 확인. 예: 1m)");
		}
	}

	/** 그 연계 학원의 장부 스프레드시트 ID. 없으면 그 학원의 장부 연결은 꺼져 있다. */
	public Optional<String> spreadsheetFor(long partnerId) {
		return Optional.ofNullable(spreadsheets.get(partnerId));
	}

	/**
	 * 장부를 정했는데 구글 시트 연결이 꺼져 있으면(서비스 계정 키 없음) 서버가 뜨지 않게 한다.
	 *
	 * @throws IllegalStateException 장부를 정했는데 연결이 꺼져 있을 때
	 */
	public void requireSheetsIfConfigured(boolean sheetsEnabled) {
		if (!spreadsheets.isEmpty() && !sheetsEnabled) {
			throw new IllegalStateException(
					"연계 학원 장부 스프레드시트를 정했는데 구글 시트 서비스 계정 키(GOOGLE_LEDGER_SA_KEY_B64)가 없다");
		}
	}

}
