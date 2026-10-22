package com.jey.core.sheets;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 구글 시트 연결 설정({@code jey.sheets.*}). 틀린 값이면 서버가 뜨지 않는다. 어느 파일에 쓸지는 쓰는 모듈의 설정이다.
 *
 * @param baseUrl 시트 API 주소. 시험에서 흉내 낸 서버로 바꾼다
 * @param connectTimeout 연결 시간 제한. 5초까지. 시트를 쓰는 리스너는 이벤트 재전송 주기(1분)보다 훨씬 빨리 끝나야 하고,
 *     billing 장부 잠금의 유지 시간(45초)이 요청 둘(연결 + 응답)보다 길어야 한다
 * @param readTimeout 응답 시간 제한. 같은 이유로 15초까지
 * @param serviceAccountKey 서비스 계정 키(JSON)의 base64. 비밀값이라 {@link #toString()}에 나오지 않는다. 없으면 시트 연결이 꺼진다
 */
@ConfigurationProperties("jey.sheets")
public record SheetsProperties(@DefaultValue("https://sheets.googleapis.com") String baseUrl,
		@DefaultValue("3s") Duration connectTimeout, @DefaultValue("10s") Duration readTimeout, String serviceAccountKey) {

	private static final Duration MAX_CONNECT_TIMEOUT = Duration.ofSeconds(5);

	private static final Duration MAX_READ_TIMEOUT = Duration.ofSeconds(15);

	public SheetsProperties {
		if (!isWithin(connectTimeout, MAX_CONNECT_TIMEOUT) || !isWithin(readTimeout, MAX_READ_TIMEOUT)) {
			throw new IllegalArgumentException("jey.sheets의 시간 제한은 연결 5초, 응답 15초까지다");
		}
	}

	private static boolean isWithin(Duration value, Duration max) {
		return value != null && value.isPositive() && value.compareTo(max) <= 0;
	}

	/** 서비스 계정 키가 있는지. */
	public boolean hasServiceAccountKey() {
		return serviceAccountKey != null && !serviceAccountKey.isBlank();
	}

	@Override
	public String toString() {
		return "SheetsProperties[baseUrl=" + baseUrl + ", connectTimeout=" + connectTimeout + ", readTimeout="
				+ readTimeout + ", serviceAccountKey=" + (hasServiceAccountKey() ? "(있음)" : "(없음)") + "]";
	}

}
