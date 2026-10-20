package com.jey.billing.application;

import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.jey.billing.domain.DiscountPolicy;

/**
 * 수납 설정({@code jey.billing.*}). 값이 없거나 잘못되면 서버가 뜨지 않는다.
 *
 * @param discountPolicy 한 라인에 할인이 여러 개일 때의 계산 방식. 이미 만든 청구는 만들 때의 방식을 남겨 두어 따라 바뀌지 않는다
 * @param referencedDiscountCodes 근거(참조값)가 있어야 붙는 할인 유형 코드. 직원이 고르는 일반 경로로는 청구에 붙지 않고, 다른 모듈이
 *     {@code InvoiceIssuing}으로만 붙인다. 빠지면 그 보호가 조용히 꺼지므로 필수다(없으면 빈 목록이 아니라 서버가 뜨지 않는다)
 */
@ConfigurationProperties("jey.billing")
record BillingProperties(DiscountPolicy discountPolicy, Set<String> referencedDiscountCodes) {

	BillingProperties {
		if (discountPolicy == null) {
			throw new IllegalArgumentException("jey.billing.discount-policy가 없다(ADDITIVE, SEQUENTIAL, SINGLE)");
		}
		if (referencedDiscountCodes == null || referencedDiscountCodes.isEmpty()) {
			throw new IllegalArgumentException("jey.billing.referenced-discount-codes가 없다(예: DONGTAN_PASS)");
		}
		referencedDiscountCodes = Set.copyOf(referencedDiscountCodes);
	}

	boolean needsReference(String discountCode) {
		return referencedDiscountCodes.contains(discountCode);
	}

}
