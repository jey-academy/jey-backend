package com.jey.dongtanpass.application;

import java.math.BigDecimal;
import java.time.Instant;

import com.jey.core.shared.Money;
import com.jey.dongtanpass.domain.PartnerRedemption;

/**
 * 연계 학원이 쓴 JEY 발급 패스와 그때 계산해 남긴 금액.
 *
 * @param redemptionId 연계 학원 사용 기록의 ID
 */
public record PartnerRedeemedPass(long redemptionId, long passId, Money originalFee, BigDecimal discountRate,
		Money discountAmount, Money payableAmount, Instant redeemedAt) {

	/** 저장한 사용 기록에서 만든다(응답의 금액이 기록과 같다). */
	static PartnerRedeemedPass of(PartnerRedemption saved) {
		return new PartnerRedeemedPass(saved.getId(), saved.getPassId(), saved.getOriginalFee(),
				saved.getDiscountRate(), saved.getDiscountAmount(), saved.getPayableAmount(), saved.getRedeemedAt());
	}

}
