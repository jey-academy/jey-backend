package com.jey.billing.domain;

import java.math.BigDecimal;

import com.jey.core.shared.Money;

/**
 * 청구 라인에 적용한 할인 하나와 그 할인이 줄인 금액.
 *
 * @param amount 이 할인이 줄인 금액(원). 할인을 순서대로 하나씩 더 적용했을 때 줄어든 만큼이다
 */
public record AppliedDiscount(String code, BigDecimal rate, Money amount) {
}
