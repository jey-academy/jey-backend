package com.jey.billing.domain;

import java.math.BigDecimal;

import com.jey.billing.api.InvoiceRules;

/**
 * 계산에 넣는 할인 하나.
 *
 * @param code 할인 유형 코드(예: {@code SIBLING_5})
 * @param rate 할인율. 5%는 0.05. 0보다 크고 1보다 작으며 소수 넷째 자리까지(할인 유형 테이블과 같다)
 * @param ref 할인을 넘긴 모듈의 참조값(예: 패스 번호). 직원이 고른 할인이면 {@code null}
 */
public record DiscountRate(String code, BigDecimal rate, String ref) {

	private static final int MAX_SCALE = InvoiceRules.MAX_RATE_SCALE;

	public DiscountRate {
		if (code == null || code.isBlank()) {
			throw new IllegalArgumentException("할인 코드는 비울 수 없다");
		}
		if (rate == null || rate.signum() <= 0 || rate.compareTo(BigDecimal.ONE) >= 0
				|| rate.stripTrailingZeros().scale() > MAX_SCALE) {
			throw new IllegalArgumentException("할인율은 0보다 크고 1보다 작으며 소수 넷째 자리까지다");
		}
	}

	/** 직원이 고른 할인(참조값 없음). */
	public DiscountRate(String code, BigDecimal rate) {
		this(code, rate, null);
	}

}
