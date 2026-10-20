package com.jey.billing.infrastructure;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;

/**
 * 서비스 계정 키로 만든 인증 머리글. 토큰을 구글에서 받아 오지 않고 키로 직접 서명한다(스코프를 담은 JWT, 구글 인증 라이브러리의
 * {@code useJwtAccessWithScope}). 그래서 인증에는 네트워크 요청이 없고, 시트 요청의 시간 제한이 한 번의 시도 전체를 묶는다.
 * 서명한 토큰은 라이브러리가 만료 전까지 다시 쓴다.
 *
 * <p>키 원문과 base64는 어디에도(문자열, 예외 문구) 싣지 않는다.
 */
final class ServiceAccountAccessTokens implements GoogleAccessTokens {

	static final String SHEETS_SCOPE = "https://www.googleapis.com/auth/spreadsheets";

	private final ServiceAccountCredentials credentials;

	private ServiceAccountAccessTokens(ServiceAccountCredentials credentials) {
		this.credentials = credentials;
	}

	/**
	 * @param keyBase64 서비스 계정 키(JSON)의 base64
	 * @throws IllegalArgumentException base64가 아니거나 서비스 계정 키가 아닐 때(서버가 뜨지 않는다)
	 */
	static ServiceAccountAccessTokens fromBase64(String keyBase64) {
		byte[] json;
		try {
			json = Base64.getDecoder().decode(keyBase64.strip());
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("장부 서비스 계정 키가 base64가 아니다");
		}
		GoogleCredentials scoped;
		try {
			scoped = ServiceAccountCredentials.fromStream(new ByteArrayInputStream(json))
				.createScoped(List.of(SHEETS_SCOPE));
		} catch (IOException | RuntimeException e) {
			// 원인 예외의 문구에 키의 일부가 실릴 수 있어 잇지 않는다.
			throw new IllegalArgumentException("장부 서비스 계정 키를 읽지 못했다(서비스 계정 키 JSON인지 확인)");
		}
		if (!(scoped instanceof ServiceAccountCredentials credentials)) {
			throw new IllegalArgumentException("장부 서비스 계정 키를 읽지 못했다(서비스 계정 키 JSON인지 확인)");
		}
		return new ServiceAccountAccessTokens(credentials.createWithUseJwtAccessWithScope(true));
	}

	@Override
	public String authorization() {
		try {
			Map<String, List<String>> metadata = credentials.getRequestMetadata();
			return metadata.get("Authorization").getFirst();
		} catch (IOException e) {
			throw new IllegalStateException("장부 인증 머리글을 만들지 못했다", e);
		}
	}

	@Override
	public String toString() {
		return "ServiceAccountAccessTokens[" + credentials.getClientEmail() + "]";
	}

}
