package com.jey.dongtanpass.application;

import java.time.Instant;

/**
 * 서명과 만료를 확인한 QR 토큰의 내용. 이미 쓴 토큰인지는 확인하지 않았다.
 *
 * @param tokenId 토큰마다 다른 값(32자 16진수). 확인하는 쪽이 재사용을 막을 때 쓴다(토큰 문자열이 아니라 이 값으로 막는다)
 */
public record VerifiedQr(long passId, String tokenId, Instant expiresAt) {
}
