package com.jey.billing.application;

import java.time.Duration;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import com.jey.billing.domain.ledger.LedgerTabName;

/**
 * 장부(구글 시트) 설정({@code jey.billing.ledger.*}). 틀린 값이면 서버가 뜨지 않는다. 구글 주소·시간 제한·서비스 계정 키는
 * 구글 시트 연결의 설정({@code jey.sheets.*})이다.
 *
 * @param spreadsheets 지점 ID → 스프레드시트 ID. 없는 지점은 장부 연결이 꺼진 것이다. 정했는데 구글 시트 연결(키)이 없으면 서버가 뜨지 않는다
 * @param retryInterval 다시 시도할 줄을 찾는 간격. 10초 이상(단위를 빠뜨린 값을 막는다)
 * @param tabName 탭 이름의 틀({@link LedgerTabName}). 기본은 실제 장부 모양 {@code {month}월 {campus}({yy})}(예: {@code 11월 동탄관(26)}).
 *     두 지점이 같은 스프레드시트를 쓰면 {@code {campus}}가 있어야 한다(없으면 두 지점의 결제가 같은 탭에 섞인다)
 */
@ConfigurationProperties("jey.billing.ledger")
public record LedgerProperties(Map<Long, String> spreadsheets, @DefaultValue("1m") Duration retryInterval,
		@DefaultValue("{month}월 {campus}({yy})") String tabName) {

	private static final Pattern SPREADSHEET_ID = Pattern.compile("[A-Za-z0-9_-]{1,200}");

	private static final Duration MIN_RETRY_INTERVAL = Duration.ofSeconds(10);

	private static final String DEFAULT_TAB_NAME = "{month}월 {campus}({yy})";

	public LedgerProperties {
		spreadsheets = spreadsheets == null ? Map.of() : Map.copyOf(spreadsheets);
		for (String id : spreadsheets.values()) {
			if (!SPREADSHEET_ID.matcher(id).matches()) {
				throw new IllegalArgumentException(
						"jey.billing.ledger.spreadsheets의 값은 스프레드시트 ID다(주소 전체가 아니라 /d/ 뒤의 부분)");
			}
		}
		if (retryInterval == null || retryInterval.compareTo(MIN_RETRY_INTERVAL) < 0) {
			throw new IllegalArgumentException("jey.billing.ledger.retry-interval은 10초 이상이다(단위를 적었는지 확인. 예: 1m)");
		}
		tabName = tabName == null ? DEFAULT_TAB_NAME : tabName;
		LedgerTabName parsed = LedgerTabName.of(tabName);
		if (!parsed.includesCampus() && new HashSet<>(spreadsheets.values()).size() < spreadsheets.size()) {
			throw new IllegalArgumentException(
					"두 지점이 같은 장부 스프레드시트를 쓰면 jey.billing.ledger.tab-name에 {campus}가 있어야 한다");
		}
	}

	/** 그 지점의 장부 스프레드시트 ID. 없으면 그 지점의 장부 연결은 꺼져 있다. */
	public Optional<String> spreadsheetFor(long campusId) {
		return Optional.ofNullable(spreadsheets.get(campusId));
	}

	/** 그 달, 그 지점의 탭 이름(예: {@code 11월 동탄관(26)}). */
	public String tabNameFor(YearMonth month, String campusName) {
		return LedgerTabName.of(tabName).format(month, campusName);
	}

	/**
	 * 장부를 정했는데 구글 시트 연결이 꺼져 있으면(서비스 계정 키 없음) 서버가 뜨지 않게 한다.
	 *
	 * @throws IllegalStateException 장부를 정했는데 연결이 꺼져 있을 때
	 */
	public void requireSheetsIfConfigured(boolean sheetsEnabled) {
		if (!spreadsheets.isEmpty() && !sheetsEnabled) {
			throw new IllegalStateException("장부 스프레드시트를 정했는데 구글 시트 서비스 계정 키(GOOGLE_LEDGER_SA_KEY_B64)가 없다");
		}
	}

}
