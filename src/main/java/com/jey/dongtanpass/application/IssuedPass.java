package com.jey.dongtanpass.application;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 발급한 패스. 링크 토큰 원문은 여기에만 있다(저장하지 않는다).
 *
 * @param linkToken 학부모 링크의 토큰 원문
 */
public record IssuedPass(long id, String passNo, String linkToken, Instant issuedAt, LocalDate expiresOn) {

	// 링크 토큰 원문이 로그에 찍히지 않게 한다.
	@Override
	public String toString() {
		return "IssuedPass[id=" + id + ", passNo=" + passNo + ", issuedAt=" + issuedAt + ", expiresOn=" + expiresOn
				+ "]";
	}

}
