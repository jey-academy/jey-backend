package com.jey.billing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

// 서비스 계정 키(base64)로 인증 머리글을 만든다. 키는 시험에서 새로 만든 것이고, 토큰 주소는 닿지 않는 곳이다
// (스스로 서명한 토큰이라 구글에 묻지 않는다는 것을 함께 본다). Docker가 필요 없다.
class ServiceAccountAccessTokensTest {

	private static final String EMAIL = TestServiceAccountKeys.EMAIL;

	private static final String keyBase64 = TestServiceAccountKeys.base64();

	@Test
	void 스스로_서명한_토큰으로_시트_권한의_인증_머리글을_만든다() {
		String authorization = ServiceAccountAccessTokens.fromBase64(keyBase64).authorization();

		assertThat(authorization).startsWith("Bearer ");
		String[] jwt = authorization.substring("Bearer ".length()).split("\\.");
		assertThat(jwt).hasSize(3);
		String payload = new String(Base64.getUrlDecoder().decode(jwt[1]), StandardCharsets.UTF_8);
		assertThat(payload).contains("\"iss\":\"" + EMAIL + "\"")
			.contains("https://www.googleapis.com/auth/spreadsheets");
	}

	@Test
	void 키가_base64가_아니거나_서비스_계정_키가_아니면_거부하고_문구에_값을_싣지_않는다() {
		assertThatThrownBy(() -> ServiceAccountAccessTokens.fromBase64("not base64 !!"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageNotContaining("not base64");
		String notAKey = Base64.getEncoder().encodeToString("{\"type\": \"authorized_user\"}".getBytes());
		assertThatThrownBy(() -> ServiceAccountAccessTokens.fromBase64(notAKey))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageNotContaining("authorized_user");
	}

	@Test
	void 문자열로_바꿔도_키가_나오지_않는다() {
		assertThat(ServiceAccountAccessTokens.fromBase64(keyBase64).toString()).doesNotContain("PRIVATE KEY")
			.doesNotContain(keyBase64.substring(0, 20));
	}

}
