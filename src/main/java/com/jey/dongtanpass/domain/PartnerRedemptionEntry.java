package com.jey.dongtanpass.domain;

import java.math.BigDecimal;
import java.time.Instant;

import com.jey.core.shared.Money;

/**
 * 연계 학원 사용 내역의 한 줄(조회 결과). 패스 번호·학생 이름(패스의 이름)과 연계 학원 이름을 붙인다.
 */
public record PartnerRedemptionEntry(Long id, Instant redeemedAt, Long partnerId, String partnerName, Long passId,
		String passNo, String studentName, Money originalFee, BigDecimal discountRate, Money discountAmount,
		Money payableAmount) {

	// 학생 이름이 로그에 찍히지 않게 한다.
	@Override
	public String toString() {
		return "PartnerRedemptionEntry[id=" + id + ", passId=" + passId + ", partnerId=" + partnerId + "]";
	}

}
