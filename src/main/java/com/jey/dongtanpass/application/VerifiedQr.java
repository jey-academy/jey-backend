package com.jey.dongtanpass.application;

import java.time.Instant;

/**
 * 서명과 만료를 확인한 QR 토큰의 내용. 이미 쓴 토큰인지는 확인하지 않았다.
 *
 * <p>{@link PassQrCodes#verify}만 만든다(생성자를 열지 않는다). 이 값을 받았다는 것이 서명을 확인했다는 뜻이다.
 */
public final class VerifiedQr {

	private final long passId;

	private final String tokenId;

	private final Instant expiresAt;

	VerifiedQr(long passId, String tokenId, Instant expiresAt) {
		this.passId = passId;
		this.tokenId = tokenId;
		this.expiresAt = expiresAt;
	}

	public long passId() {
		return passId;
	}

	/** 토큰마다 다른 값(32자 16진수). 확인하는 쪽이 재사용을 막을 때 쓴다(토큰 문자열이 아니라 이 값으로 막는다). */
	public String tokenId() {
		return tokenId;
	}

	public Instant expiresAt() {
		return expiresAt;
	}

	@Override
	public String toString() {
		return "VerifiedQr[passId=" + passId + ", tokenId=" + tokenId + ", expiresAt=" + expiresAt + "]";
	}

}
