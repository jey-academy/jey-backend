package com.jey.core.campus.web;

import java.math.BigDecimal;

import com.jey.core.campus.domain.DiscountType;

/**
 * @param rate 할인율. 5%는 0.05
 * @param active 꺼진 할인은 새로 고를 수 없다
 */
record DiscountTypeResponse(Long id, String code, String name, BigDecimal rate, boolean active) {

	static DiscountTypeResponse from(DiscountType discountType) {
		return new DiscountTypeResponse(discountType.getId(), discountType.getCode(), discountType.getName(),
				discountType.getRate(), discountType.isActive());
	}

}
