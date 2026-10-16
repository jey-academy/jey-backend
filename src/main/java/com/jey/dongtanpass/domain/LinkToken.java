package com.jey.dongtanpass.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * 학부모에게 보내는 링크의 토큰. 256비트 난수라 추측할 수 없다.
 * 원문은 발급과 링크 새로 만들기의 응답에만 나가고 DB에 저장하지 않는다. 저장하는 것은 SHA-256 해시다.
 * 받은 원문으로 해시를 다시 계산해 패스를 찾는다.
 * 다만 두 응답은 멱등 응답으로 보관되므로(ADR-0027) 원문이 담긴 응답이 보관 기간(24시간) 동안 저장소에 남는다(ADR-0035).
 *
 * @param raw 원문(Base64url, 패딩 없음, 43자)
 * @param hash 원문의 SHA-256(소문자 16진수, 64자)
 */
public record LinkToken(String raw, String hash) {

	private static final int BYTES = 32;

	// 32바이트의 Base64url(패딩 없음)
	private static final Pattern RAW = Pattern.compile("[A-Za-z0-9_-]{43}");

	public static LinkToken generate(SecureRandom random) {
		byte[] bytes = new byte[BYTES];
		random.nextBytes(bytes);
		String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		return new LinkToken(raw, hashOf(raw));
	}

	/**
	 * 받은 원문이 이 토큰의 모양인지. 해시하기 전에 본다. 모양이 틀린 토큰은 없는 토큰과 같이 답한다
	 * (어느 쪽인지 드러내지 않는다). {@link #hashOf}는 ASCII가 아닌 글자를 {@code ?}로 바꾸므로 그 전에 걸러야 한다.
	 */
	public static boolean isWellFormed(String raw) {
		return raw != null && RAW.matcher(raw).matches();
	}

	public static String hashOf(String raw) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.US_ASCII)));
		}
		catch (NoSuchAlgorithmException ex) {
			// 모든 JVM에 있어야 하는 알고리즘이다.
			throw new IllegalStateException("SHA-256을 쓸 수 없다", ex);
		}
	}

	// 원문이 로그에 찍히지 않게 한다.
	@Override
	public String toString() {
		return "LinkToken[hash=" + hash + "]";
	}

}
