package com.jey.billing.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.jey.billing.domain.DiscountPolicy;

/**
 * 수납 설정({@code jey.billing.*}). 값이 없거나 잘못되면 서버가 뜨지 않는다.
 *
 * @param discountPolicy 한 라인에 할인이 여러 개일 때의 계산 방식. 이미 만든 청구는 만들 때의 방식을 남겨 두어 따라 바뀌지 않는다
 */
@ConfigurationProperties("jey.billing")
record BillingProperties(DiscountPolicy discountPolicy) {

	BillingProperties {
		if (discountPolicy == null) {
			throw new IllegalArgumentException("jey.billing.discount-policy가 없다(ADDITIVE, SEQUENTIAL, SINGLE)");
		}
	}

}
