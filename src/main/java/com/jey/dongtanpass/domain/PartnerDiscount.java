package com.jey.dongtanpass.domain;

import java.math.BigDecimal;

import com.jey.core.shared.Money;
import com.jey.core.shared.RoundingPolicy;

/**
 * 연계 학원이 JEY 발급 패스로 할인해 줄 금액. 받을 금액 = 원래 수강료 × (1 − 할인율)에서 1원 미만을 버린 값,
 * 할인액 = 원래 수강료 − 받을 금액(둘을 더하면 원래 수강료다). 할인율은 패스에 남은 발급 때 값을 넘겨받는다.
 *
 * @param originalFee 원래 수강료(첫 달 한 달 치)
 */
public record PartnerDiscount(Money originalFee, BigDecimal discountRate, Money discountAmount,
		Money payableAmount) {

	/** 원래 수강료의 상한. 첫 달 수강료로는 넘을 일이 없는 값이라 입력 실수(0을 더 친 것 등)를 막는다. */
	public static final Money MAX_ORIGINAL_FEE = Money.of(10_000_000);

	/**
	 * @throws IllegalArgumentException 빈 값, 0원이거나 1원 단위가 아니거나 상한을 넘는 수강료, 규칙 밖의 할인율({@link Pass#validRate})
	 */
	public static PartnerDiscount of(Money originalFee, BigDecimal discountRate) {
		if (originalFee == null) {
			throw new IllegalArgumentException("originalFee는 비울 수 없다");
		}
		if (originalFee.isZero() || !originalFee.isWholeWon()) {
			throw new IllegalArgumentException("원래 수강료는 0원보다 크고 1원 단위여야 한다");
		}
		if (originalFee.compareTo(MAX_ORIGINAL_FEE) > 0) {
			throw new IllegalArgumentException("원래 수강료는 " + MAX_ORIGINAL_FEE.amount().toPlainString() + "원을 넘을 수 없다");
		}
		BigDecimal rate = Pass.validRate(discountRate);
		// 곱셈을 모두 끝낸 뒤 마지막에 한 번 끊는다(ADR-0015).
		Money payable = originalFee.times(BigDecimal.ONE.subtract(rate)).round(RoundingPolicy.ONE_WON_DOWN);
		return new PartnerDiscount(originalFee, rate, originalFee.minus(payable), payable);
	}

}
