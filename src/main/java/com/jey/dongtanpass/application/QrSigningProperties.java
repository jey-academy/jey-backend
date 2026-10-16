package com.jey.dongtanpass.application;

import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 패스 QR 토큰의 서명 키 설정({@code jey.dongtanpass.qr.*}, ADR-0010). 값이 없거나 잘못되면 서버가 뜨지 않는다.
 *
 * <p>키는 버전별로 여러 개를 둔다. 새로 만드는 토큰은 {@code activeKeyVersion}의 키로 서명하고, 확인할 때는 토큰에 적힌 버전의 키를 쓴다.
 * 키를 바꿀 때는 새 버전을 더하고 {@code activeKeyVersion}을 올린 뒤, 옛 버전의 토큰이 모두 만료되면(1분) 옛 키를 지운다.
 *
 * @param activeKeyVersion 새로 만드는 토큰을 서명하는 키의 버전. 1 이상
 * @param keys 버전 → 서명 키(Base64로 적은 32바이트 이상)
 */
@ConfigurationProperties("jey.dongtanpass.qr")
record QrSigningProperties(int activeKeyVersion, Map<Integer, String> keys) {

	private static final String PREFIX = "jey.dongtanpass.qr.";

	// HMAC-SHA256의 출력 길이보다 짧은 키는 추측하기 쉬워진다.
	private static final int MIN_KEY_BYTES = 32;

	// 오류 문구에 키 값을 싣지 않는다. 문구는 로그에 남는다.
	QrSigningProperties {
		if (activeKeyVersion < 1) {
			throw new IllegalArgumentException(PREFIX + "active-key-version은 1 이상이어야 한다");
		}
		if (keys == null || keys.isEmpty()) {
			throw new IllegalArgumentException(PREFIX + "keys가 없다");
		}
		// 복사하기 전에 본다. Map.copyOf는 널 키·값을 문구 없는 NPE로 떨어뜨린다.
		keys.forEach((version, key) -> {
			if (version == null || version < 1) {
				throw new IllegalArgumentException(PREFIX + "keys의 버전은 1 이상이어야 한다");
			}
			if (decode(key, version).length < MIN_KEY_BYTES) {
				throw new IllegalArgumentException(
						PREFIX + "keys." + version + "는 Base64로 적은 " + MIN_KEY_BYTES + "바이트 이상이어야 한다");
			}
		});
		keys = Map.copyOf(keys);
		if (!keys.containsKey(activeKeyVersion)) {
			throw new IllegalArgumentException(PREFIX + "keys에 active-key-version(" + activeKeyVersion + ")의 키가 없다");
		}
	}

	/** 그 버전의 키. 없는 버전이면 {@code null}. */
	byte[] keyBytes(int version) {
		String key = keys.get(version);
		return (key != null) ? decode(key, version) : null;
	}

	private static byte[] decode(String key, int version) {
		String name = PREFIX + "keys." + version;
		if (key == null || key.isBlank()) {
			throw new IllegalArgumentException(name + "가 없다");
		}
		// 환경변수가 없으면 "${...}"라는 글자가 그대로 들어온다.
		if (key.contains("${")) {
			throw new IllegalArgumentException(name + "의 환경변수가 설정되지 않았다");
		}
		try {
			return Base64.getDecoder().decode(key.strip());
		}
		catch (IllegalArgumentException ex) {
			// 원인 예외의 문구에 키의 일부가 들어 있을 수 있어 붙이지 않는다.
			throw new IllegalArgumentException(name + "는 Base64 형식이어야 한다");
		}
	}

	// 키가 로그에 찍히지 않게 한다.
	@Override
	public String toString() {
		return "QrSigningProperties[activeKeyVersion=" + activeKeyVersion + ", keyVersions="
				+ new TreeMap<>(keys).keySet() + "]";
	}

}
