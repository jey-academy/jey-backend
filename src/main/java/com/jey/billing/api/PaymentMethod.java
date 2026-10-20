package com.jey.billing.api;

/** 데스크에서 받은 결제의 수단. 결제 완료 이벤트에 실린다. 온라인 결제는 붙일 때 더한다. */
public enum PaymentMethod {

	/** 포스기 카드 결제. */
	CARD_POS,

	CASH

}
