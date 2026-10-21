package com.jey.billing.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** 시험에서 새로 만든 서비스 계정 키(JSON의 base64). 실제 구글 계정이 아니다. 토큰 주소는 닿지 않는 곳이다. */
public final class TestServiceAccountKeys {

	public static final String EMAIL = "jey-ledger-writer@jey-ledger.iam.gserviceaccount.com";

	private static final String KEY = make();

	private TestServiceAccountKeys() {
	}

	public static String base64() {
		return KEY;
	}

	private static String make() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			String pem = "-----BEGIN PRIVATE KEY-----\n"
					+ Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
					+ "\n-----END PRIVATE KEY-----\n";
			String json = """
					{"type": "service_account", "project_id": "jey-ledger", "private_key_id": "test-key-id",
					 "private_key": "%s", "client_email": "%s", "client_id": "1",
					 "token_uri": "http://127.0.0.1:1/token"}""".formatted(pem, EMAIL);
			return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

}
