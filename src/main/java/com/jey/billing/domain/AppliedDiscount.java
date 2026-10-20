package com.jey.billing.domain;

import java.math.BigDecimal;

import com.jey.core.shared.Money;

/**
 * 청구 라인에 적용한 할인 하나와 그 할인이 줄인 금액.
 *
 * @param amount 이 할인이 줄인 금액(원). 할인을 순서대로 하나씩 더 적용했을 때 줄어든 만큼이다
 * @param ref 할인을 넘긴 모듈의 참조값. 직원이 고른 할인이면 {@code null}
 */
public record AppliedDiscount(String code, BigDecimal rate, Money amount, String ref) {

	/** 직원이 고른 할인(참조값 없음). */
	public AppliedDiscount(String code, BigDecimal rate, Money amount) {
		this(code, rate, amount, null);
	}

}
