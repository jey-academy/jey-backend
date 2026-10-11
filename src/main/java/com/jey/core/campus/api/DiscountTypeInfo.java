package com.jey.core.campus.api;

import java.math.BigDecimal;

/**
 * 할인 유형. 조회한 시점의 값이다.
 *
 * @param rate 할인율. 5%는 0.05
 * @param active 꺼진 할인은 새로 고를 수 없다
 */
public record DiscountTypeInfo(long id, String code, String name, BigDecimal rate, boolean active) {
}
