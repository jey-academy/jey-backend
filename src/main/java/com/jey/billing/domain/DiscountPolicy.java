package com.jey.billing.domain;

/**
 * 한 청구 라인에 할인이 여러 개일 때 계산하는 방식. 설정값으로 고른다(기본 합산).
 */
public enum DiscountPolicy {

	/** 할인율을 더해 한 번에 뺀다. 5% + 5% = 10%. */
	ADDITIVE,

	/** 앞의 할인을 뺀 금액에서 다음 할인을 뺀다. 5% 다음 5% = 9.75%. */
	SEQUENTIAL,

	/** 할인을 하나만 받는다. 둘 이상이면 거부한다(어느 것을 남길지 앱이 고르지 않는다). */
	SINGLE

}
