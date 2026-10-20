package com.jey.dongtanpass.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * JEY 지점이 발급한 패스. 링크 토큰 원문은 이 값에만 있다.
 *
 * @param discountRate 발급 때 패스에 남긴 할인율
 */
public record CampusIssuedPass(long id, String passNo, String linkToken, Instant issuedAt, LocalDate expiresOn,
		BigDecimal discountRate) {

	// 링크 토큰 원문이 로그에 찍히지 않게 한다.
	@Override
	public String toString() {
		return "CampusIssuedPass[id=" + id + ", passNo=" + passNo + ", issuedAt=" + issuedAt + ", expiresOn="
				+ expiresOn + ", discountRate=" + discountRate + "]";
	}

}
