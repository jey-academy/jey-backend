package com.jey.billing.domain;

import java.math.BigDecimal;

/**
 * 계산에 넣는 할인 하나.
 *
 * @param code 할인 유형 코드(예: {@code SIBLING_5})
 * @param rate 할인율. 5%는 0.05. 0보다 크고 1보다 작으며 소수 넷째 자리까지(할인 유형 테이블과 같다)
 */
public record DiscountRate(String code, BigDecimal rate) {

	private static final int MAX_SCALE = 4;

	public DiscountRate {
		if (code == null || code.isBlank()) {
			throw new IllegalArgumentException("할인 코드는 비울 수 없다");
		}
		if (rate == null || rate.signum() <= 0 || rate.compareTo(BigDecimal.ONE) >= 0
				|| rate.stripTrailingZeros().scale() > MAX_SCALE) {
			throw new IllegalArgumentException("할인율은 0보다 크고 1보다 작으며 소수 넷째 자리까지다");
		}
	}

}
