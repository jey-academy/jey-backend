package com.jey.dongtanpass.domain;

import java.math.BigDecimal;

import com.jey.core.shared.Money;
import com.jey.core.shared.RoundingPolicy;

/**
 * 연계 학원이 JEY 발급 패스로 할인해 줄 금액. 받을 금액 = 원래 수강료 × (1 − 할인율)에서 1원 미만을 버린 값,
 * 할인액 = 원래 수강료 − 받을 금액(둘을 더하면 원래 수강료다). 할인율은 패스에 남은 발급 때 값을 넘겨받는다
 * ({@link Pass#partnerDiscountFor}).
 *
 * <p>{@link #of}로만 만든다(생성자로는 서로 맞지 않는 금액을 만들 수 있다. {@code DongtanpassRulesTest}가 막는다).
 *
 * @param originalFee 원래 수강료(첫 달 한 달 치)
 */
public record PartnerDiscount(Money originalFee, BigDecimal discountRate, Money discountAmount,
		Money payableAmount) {

	/** 원래 수강료의 상한. 첫 달 수강료로는 넘을 일이 없는 값이라 입력 실수(0을 더 친 것 등)를 막는다. */
	public static final Money MAX_ORIGINAL_FEE = Money.of(10_000_000);

	/**
	 * 원래 수강료의 규칙: 0원보다 크고, 1원 단위이고, {@link #MAX_ORIGINAL_FEE} 이하다. 규칙은 여기 한 곳이다
	 * (서비스는 이것으로 먼저 보고 업무 오류로 답한다).
	 */
	public static boolean acceptsFee(Money originalFee) {
		return originalFee != null && !originalFee.isZero() && originalFee.isWholeWon()
				&& originalFee.compareTo(MAX_ORIGINAL_FEE) <= 0;
	}

	/**
	 * @throws IllegalArgumentException 규칙 밖의 수강료({@link #acceptsFee}), 규칙 밖의 할인율({@link Pass#validRate})
	 */
	public static PartnerDiscount of(Money originalFee, BigDecimal discountRate) {
		if (!acceptsFee(originalFee)) {
			throw new IllegalArgumentException("원래 수강료는 0원보다 크고 1원 단위이며 "
					+ MAX_ORIGINAL_FEE.amount().toPlainString() + "원을 넘을 수 없다");
		}
		BigDecimal rate = Pass.validRate(discountRate);
		// 곱셈을 모두 끝낸 뒤 마지막에 한 번 끊는다(ADR-0015).
		Money payable = originalFee.times(BigDecimal.ONE.subtract(rate)).round(RoundingPolicy.ONE_WON_DOWN);
		return new PartnerDiscount(originalFee, rate, originalFee.minus(payable), payable);
	}

}
