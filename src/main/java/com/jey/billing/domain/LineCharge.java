package com.jey.billing.domain;

import java.util.List;

import com.jey.core.shared.Money;

/**
 * 청구 라인 하나의 계산 결과. {@code listAmount = chargeAmount + 할인 금액의 합}이 언제나 맞는다.
 *
 * @param listAmount 할인 없는 그 횟수의 금액(기준 수강료 × 횟수 ÷ 8, 1원 미만 버림)
 * @param discounts 받은 순서대로
 * @param chargeAmount 청구액
 */
public record LineCharge(Money listAmount, List<AppliedDiscount> discounts, Money chargeAmount) {

	public LineCharge {
		discounts = List.copyOf(discounts);
	}

}
