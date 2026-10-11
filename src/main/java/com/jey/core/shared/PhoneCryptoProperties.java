package com.jey.core.shared;

import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 전화번호 암호화 설정({@code jey.crypto.phone.*}). 값이 없거나 잘못되면 서버가 뜨지 않는다.
 *
 * <p>암호화 키는 버전별로 여러 개를 둔다. 새로 저장하는 값은 {@code activeKeyVersion}의 키로 암호화하고,
 * 읽을 때는 그 값이 저장될 때 쓴 버전의 키로 푼다. 키를 교체할 때는 새 버전을 추가하고 {@code activeKeyVersion}을 올린다.
 * 옛 버전의 키는 그 버전으로 저장된 값이 남아 있는 동안 지우지 않는다.
 *
 * @param activeKeyVersion 새로 저장할 때 쓰는 암호화 키의 버전. 1 이상
 * @param encryptionKeys 버전 → 암호화 키(Base64로 적은 32바이트)
 * @param hmacKey 검색용 인덱스를 만드는 키(Base64로 적은 32바이트 이상). 암호화 키와 다른 값이어야 한다.
 * 바꾸면 이미 저장된 인덱스와 맞지 않아 검색이 되지 않으므로, 모든 행의 인덱스를 다시 계산해야 한다.
 */
@ConfigurationProperties("jey.crypto.phone")
record PhoneCryptoProperties(int activeKeyVersion, Map<Integer, String> encryptionKeys, String hmacKey) {

	private static final String PREFIX = "jey.crypto.phone.";

	// AES-256
	private static final int ENCRYPTION_KEY_BYTES = 32;

	// HMAC-SHA256의 출력 길이보다 짧은 키는 추측하기 쉬워진다.
	private static final int MIN_HMAC_KEY_BYTES = 32;

	// 오류 문구에 키 값을 싣지 않는다. 문구는 로그에 남는다.
	PhoneCryptoProperties {
		if (activeKeyVersion < 1) {
			throw new IllegalArgumentException(PREFIX + "active-key-version은 1 이상이어야 한다");
		}
		if (encryptionKeys == null || encryptionKeys.isEmpty()) {
			throw new IllegalArgumentException(PREFIX + "encryption-keys가 없다");
		}
		encryptionKeys = Map.copyOf(encryptionKeys);
		encryptionKeys.forEach((version, key) -> {
			if (version == null || version < 1) {
				throw new IllegalArgumentException(PREFIX + "encryption-keys의 버전은 1 이상이어야 한다");
			}
			if (decode(key, "encryption-keys." + version).length != ENCRYPTION_KEY_BYTES) {
				throw new IllegalArgumentException(
						PREFIX + "encryption-keys." + version + "은 Base64로 적은 " + ENCRYPTION_KEY_BYTES + "바이트여야 한다");
			}
		});
		if (!encryptionKeys.containsKey(activeKeyVersion)) {
			throw new IllegalArgumentException(
					PREFIX + "encryption-keys에 active-key-version(" + activeKeyVersion + ")의 키가 없다");
		}
		if (decode(hmacKey, "hmac-key").length < MIN_HMAC_KEY_BYTES) {
			throw new IllegalArgumentException(PREFIX + "hmac-key는 Base64로 적은 " + MIN_HMAC_KEY_BYTES + "바이트 이상이어야 한다");
		}
		// 한 키가 새면 암호문과 인덱스가 같이 뚫린다. 두 키를 나눠 둔 뜻이 없어진다.
		if (encryptionKeys.containsValue(hmacKey)) {
			throw new IllegalArgumentException(PREFIX + "hmac-key는 암호화 키와 달라야 한다");
		}
	}

	byte[] encryptionKeyBytes(int version) {
		String key = encryptionKeys.get(version);
		return (key != null) ? decode(key, "encryption-keys." + version) : null;
	}

	byte[] hmacKeyBytes() {
		return decode(hmacKey, "hmac-key");
	}

	private static byte[] decode(String key, String name) {
		if (key == null || key.isBlank()) {
			throw new IllegalArgumentException(PREFIX + name + "가 없다");
		}
		// 환경변수가 없으면 "${...}"라는 글자가 그대로 들어온다.
		if (key.contains("${")) {
			throw new IllegalArgumentException(PREFIX + name + "의 환경변수가 설정되지 않았다");
		}
		try {
			return Base64.getDecoder().decode(key.strip());
		}
		catch (IllegalArgumentException ex) {
			// 원인 예외의 문구에 키의 일부가 들어 있을 수 있어 붙이지 않는다.
			throw new IllegalArgumentException(PREFIX + name + "는 Base64 형식이어야 한다");
		}
	}

	// 키가 로그에 찍히지 않게 한다.
	@Override
	public String toString() {
		return "PhoneCryptoProperties[activeKeyVersion=" + activeKeyVersion + ", keyVersions="
				+ new TreeMap<>(encryptionKeys).keySet() + "]";
	}

}
