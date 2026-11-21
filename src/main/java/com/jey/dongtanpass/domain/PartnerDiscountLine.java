package com.jey.dongtanpass.domain;

import java.math.BigDecimal;
import java.time.Instant;

import com.jey.core.shared.Money;

/**
 * 연계 학원 장부 CSV의 "할인해 준 기록" 한 줄(조회 결과). 패스 번호·학생 이름(패스의 이름)과 패스를 발급한 JEY 지점을 붙인다.
 */
public record PartnerDiscountLine(long passId, Instant redeemedAt, String studentName, String passNo, Money originalFee,
		BigDecimal discountRate, Money discountAmount, Money payableAmount, Long issuerCampusId) {

	// 학생 이름이 로그에 찍히지 않게 한다.
	@Override
	public String toString() {
		return "PartnerDiscountLine[passId=" + passId + "]";
	}

}
