package com.jey.billing.application;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 장부(구글 시트) 연결 설정({@code jey.billing.ledger.*}). 틀린 값이면 서버가 뜨지 않는다.
 *
 * @param baseUrl 시트 API 주소. 시험에서 흉내 낸 서버로 바꾼다
 * @param spreadsheets 지점 ID → 스프레드시트 ID. 없는 지점은 장부 연결이 꺼진 것이다
 * @param connectTimeout 연결 시간 제한. 5초까지. 한 번의 시도(잠금 대기 5초 + 구글 요청 둘 × (연결 + 응답))가 이벤트 재전송 주기(1분)
 *     안에 끝나야 하고, 장부 잠금의 유지 시간({@code RedisLedgerLock.TTL}, 45초)이 요청 둘보다 길어야 한다
 * @param readTimeout 응답 시간 제한. 같은 이유로 15초까지
 * @param serviceAccountKey 서비스 계정 키(JSON)의 base64. 비밀값이라 {@link #toString()}에 나오지 않는다
 * @param retryInterval 다시 시도할 줄을 찾는 간격. 10초 이상(단위를 빠뜨린 값을 막는다)
 */
@ConfigurationProperties("jey.billing.ledger")
public record LedgerProperties(@DefaultValue("https://sheets.googleapis.com") String baseUrl,
		Map<Long, String> spreadsheets, @DefaultValue("3s") Duration connectTimeout,
		@DefaultValue("10s") Duration readTimeout, String serviceAccountKey,
		@DefaultValue("1m") Duration retryInterval) {

	private static final Pattern SPREADSHEET_ID = Pattern.compile("[A-Za-z0-9_-]{1,200}");

	private static final Duration MAX_CONNECT_TIMEOUT = Duration.ofSeconds(5);

	private static final Duration MAX_READ_TIMEOUT = Duration.ofSeconds(15);

	private static final Duration MIN_RETRY_INTERVAL = Duration.ofSeconds(10);

	public LedgerProperties {
		spreadsheets = spreadsheets == null ? Map.of() : Map.copyOf(spreadsheets);
		for (String id : spreadsheets.values()) {
			if (!SPREADSHEET_ID.matcher(id).matches()) {
				throw new IllegalArgumentException(
						"jey.billing.ledger.spreadsheets의 값은 스프레드시트 ID다(주소 전체가 아니라 /d/ 뒤의 부분)");
			}
		}
		if (!spreadsheets.isEmpty() && (serviceAccountKey == null || serviceAccountKey.isBlank())) {
			throw new IllegalArgumentException("장부 스프레드시트를 정했는데 서비스 계정 키(GOOGLE_LEDGER_SA_KEY_B64)가 없다");
		}
		if (!isWithin(connectTimeout, MAX_CONNECT_TIMEOUT) || !isWithin(readTimeout, MAX_READ_TIMEOUT)) {
			throw new IllegalArgumentException("jey.billing.ledger의 시간 제한은 연결 5초, 응답 15초까지다");
		}
		if (retryInterval == null || retryInterval.compareTo(MIN_RETRY_INTERVAL) < 0) {
			throw new IllegalArgumentException("jey.billing.ledger.retry-interval은 10초 이상이다(단위를 적었는지 확인. 예: 1m)");
		}
	}

	private static boolean isWithin(Duration value, Duration max) {
		return value != null && value.isPositive() && value.compareTo(max) <= 0;
	}

	/** 그 지점의 장부 스프레드시트 ID. 없으면 그 지점의 장부 연결은 꺼져 있다. */
	public Optional<String> spreadsheetFor(long campusId) {
		return Optional.ofNullable(spreadsheets.get(campusId));
	}

	/** 서비스 계정 키가 있는지. 없으면 구글에 요청할 수 없다(스프레드시트도 없다 — 생성자가 막는다). */
	public boolean hasServiceAccountKey() {
		return serviceAccountKey != null && !serviceAccountKey.isBlank();
	}

	@Override
	public String toString() {
		return "LedgerProperties[baseUrl=" + baseUrl + ", spreadsheets=" + spreadsheets + ", connectTimeout="
				+ connectTimeout + ", readTimeout=" + readTimeout + ", serviceAccountKey="
				+ (hasServiceAccountKey() ? "(있음)" : "(없음)") + ", retryInterval=" + retryInterval + "]";
	}

}
