package com.jey.dongtanpass.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.jey.core.shared.Money;

/**
 * 연계 학원 사용 내역의 한 줄.
 *
 * @param studentName 패스의 학생 이름(발급 때의 이름. 연계 학원에 제공하는 것은 고지·동의의 범위다)
 * @param redeemedOn 사용일(한국 날짜)
 */
public record PartnerRedemptionRow(long id, Instant redeemedAt, LocalDate redeemedOn, long partnerId,
		String partnerName, long passId, String passNo, String studentName, Money originalFee, BigDecimal discountRate,
		Money discountAmount, Money payableAmount) {

	// 학생 이름이 로그에 찍히지 않게 한다.
	@Override
	public String toString() {
		return "PartnerRedemptionRow[id=" + id + ", passId=" + passId + ", partnerId=" + partnerId + "]";
	}

}
