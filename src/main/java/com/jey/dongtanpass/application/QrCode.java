package com.jey.dongtanpass.application;

import java.time.Instant;

/**
 * 학부모 화면의 QR에 넣을 토큰.
 *
 * @param expiresAt 이 시각부터 무효다. 화면은 그 전에 새로 받는다
 */
public record QrCode(String token, Instant expiresAt) {

	// 토큰이 로그에 찍히지 않게 한다.
	@Override
	public String toString() {
		return "QrCode[expiresAt=" + expiresAt + "]";
	}

}
