package com.jey.dongtanpass.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 학부모 화면의 QR에 넣는 서명 토큰을 만들고 확인한다(ADR-0010). 만드는 쪽(학부모 QR)과 확인하는 쪽(데스크, 연계 학원)이 이것 하나를 쓴다.
 *
 * <p>토큰은 {@code {키 버전}.{본문}.{서명}}이다. 본문은 패스 ID, 만료 시각(초), 무작위 토큰 ID 16바이트를 이은 32바이트이고,
 * 서명은 용도 문자열({@code dongtanpass.pass|}) 뒤에 {@code {키 버전}.{본문}}을 이은 것의 HMAC-SHA256이다. 본문과 서명은 Base64url(패딩 없음)이다.
 * 만들 때 저장하지 않는다. 같은 토큰을 두 번 쓰지 못하게 하는 것은 확인하는 쪽의 일이다(토큰 ID를 남은 수명만큼 적는다).
 *
 * <p>시각은 부르는 쪽이 준다(주입한 시계에서 읽은 값).
 */
@Component
@EnableConfigurationProperties(QrSigningProperties.class)
public class PassQrCodes {

	/** 토큰의 수명. 만드는 시각을 초로 내린 데서 센다(그래서 실제로는 59초 넘게 60초 이하). 만료 시각 정각부터 무효다. 화면은 그 전에 새로 받는다. */
	public static final Duration TTL = Duration.ofSeconds(60);

	private static final Logger log = LoggerFactory.getLogger(PassQrCodes.class);

	private static final String HMAC = "HmacSHA256";

	// 서명 입력의 맨 앞에 붙인다. 같은 키를 다른 QR(쿠폰 등)에 쓰더라도 그 토큰이 패스 토큰으로 통과하지 않는다. 토큰에는 싣지 않는다.
	private static final String PURPOSE = "dongtanpass.pass|";

	private static final int TOKEN_ID_BYTES = 16;

	// 패스 ID(long) + 만료 시각(long) + 토큰 ID
	private static final int BODY_BYTES = Long.BYTES + Long.BYTES + TOKEN_ID_BYTES;

	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

	private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

	private final SecureRandom random = new SecureRandom();

	private final int activeKeyVersion;

	private final Map<Integer, SecretKeySpec> keys = new HashMap<>();

	PassQrCodes(QrSigningProperties properties) {
		this.activeKeyVersion = properties.activeKeyVersion();
		properties.keys()
			.keySet()
			.forEach(version -> keys.put(version, new SecretKeySpec(properties.keyBytes(version), HMAC)));
	}

	/**
	 * 그 패스의 토큰을 지금 버전의 키로 만든다. 쓸 수 있는 패스인지는 부르는 쪽이 먼저 본다.
	 * 만료 시각은 초 단위다(지금을 초로 내린 뒤 수명을 더한다). 본문에 적는 값과 돌려주는 값이 같다.
	 */
	public QrCode issue(long passId, Instant now) {
		Instant expiresAt = now.truncatedTo(ChronoUnit.SECONDS).plus(TTL);
		byte[] tokenId = new byte[TOKEN_ID_BYTES];
		random.nextBytes(tokenId);
		byte[] body = ByteBuffer.allocate(BODY_BYTES)
			.putLong(passId)
			.putLong(expiresAt.getEpochSecond())
			.put(tokenId)
			.array();
		String signed = activeKeyVersion + "." + ENCODER.encodeToString(body);
		return new QrCode(signed + "." + ENCODER.encodeToString(sign(keys.get(activeKeyVersion), signed)), expiresAt);
	}

	/**
	 * 토큰을 확인한다. 모양, 키 버전, 서명, 만료 중 하나라도 맞지 않으면 빈 값이다(이유를 가리지 않는다. 예외를 던지지 않는다).
	 * 같은 토큰을 이미 썼는지는 보지 않는다.
	 *
	 * <p>무효인 이유는 로그에만 남긴다({@code reason=malformed|unknown_key_version|bad_signature|signed_bad_body|expired},
	 * 토큰 원문 없이). 모르는 키 버전(키 회전이 어긋남)과 서명은 맞는데 본문이 틀린 것(만드는 쪽의 버그나 키 유출)은 경고로 남긴다.
	 */
	public Optional<VerifiedQr> verify(String token, Instant now) {
		if (token == null) {
			return invalid("malformed");
		}
		String[] parts = token.split("\\.", -1);
		if (parts.length != 3 || !isVersion(parts[0])) {
			return invalid("malformed");
		}
		SecretKeySpec key = keys.get(Integer.parseInt(parts[0]));
		if (key == null) {
			log.warn("QR 토큰 확인 실패: reason=unknown_key_version keyVersion={}", parts[0]);
			return Optional.empty();
		}
		byte[] body;
		byte[] signature;
		try {
			body = DECODER.decode(parts[1]);
			signature = DECODER.decode(parts[2]);
		}
		catch (IllegalArgumentException ex) {
			return invalid("malformed");
		}
		// 해독기는 패딩과 마지막 글자의 쓰이지 않는 비트를 눈감아 준다. 만든 그대로의 글자만 받아 한 토큰이 한 문자열이게 한다.
		if (!ENCODER.encodeToString(signature).equals(parts[2])) {
			return invalid("malformed");
		}
		// 길이가 달라도 같은 시간에 끝나는 비교. 서명을 한 글자씩 맞춰 보는 공격을 막는다.
		if (!MessageDigest.isEqual(sign(key, parts[0] + "." + parts[1]), signature)) {
			return invalid("bad_signature");
		}
		// 우리 키로 서명했는데 본문이 틀렸다. 만드는 쪽의 버그이거나 키가 샌 것이라 알아채야 한다.
		if (body.length != BODY_BYTES) {
			log.warn("QR 토큰 확인 실패: reason=signed_bad_body");
			return Optional.empty();
		}
		ByteBuffer buffer = ByteBuffer.wrap(body);
		long passId = buffer.getLong();
		Instant expiresAt = Instant.ofEpochSecond(buffer.getLong());
		byte[] tokenId = new byte[TOKEN_ID_BYTES];
		buffer.get(tokenId);
		if (!now.isBefore(expiresAt)) {
			return invalid("expired");
		}
		return Optional.of(new VerifiedQr(passId, HexFormat.of().formatHex(tokenId), expiresAt));
	}

	private static Optional<VerifiedQr> invalid(String reason) {
		log.info("QR 토큰 확인 실패: reason={}", reason);
		return Optional.empty();
	}

	// 키 버전은 ASCII 숫자 아홉 자까지다(int에 들어간다).
	// Character.isDigit은 다른 문자의 숫자(١, １)도 참이고, 그런 글자를 parseInt가 받는지는 JDK에 따라 다르다.
	private static boolean isVersion(String version) {
		return !version.isEmpty() && version.length() <= 9 && version.chars().allMatch(c -> c >= '0' && c <= '9');
	}

	private static byte[] sign(SecretKeySpec key, String signed) {
		try {
			Mac mac = Mac.getInstance(HMAC);
			mac.init(key);
			return mac.doFinal((PURPOSE + signed).getBytes(StandardCharsets.US_ASCII));
		}
		catch (GeneralSecurityException ex) {
			// 모든 JVM에 있어야 하는 알고리즘이고 키는 시작할 때 확인했다.
			throw new IllegalStateException("QR 토큰을 서명할 수 없다", ex);
		}
	}

	// 키가 로그에 찍히지 않게 한다.
	@Override
	public String toString() {
		return "PassQrCodes[activeKeyVersion=" + activeKeyVersion + "]";
	}

}
