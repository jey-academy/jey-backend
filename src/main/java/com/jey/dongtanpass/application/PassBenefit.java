package com.jey.dongtanpass.application;

import java.math.BigDecimal;

/**
 * 패스를 받는 지점과 할인. 조회한 시점의 값이다.
 *
 * @param discountRate 할인율. 5%는 0.05
 */
public record PassBenefit(long campusId, String campusName, String discountTypeCode, BigDecimal discountRate) {
}
