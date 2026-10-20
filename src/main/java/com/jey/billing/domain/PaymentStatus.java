package com.jey.billing.domain;

/** 결제의 상태. 데스크 기록이라 요청·승인 단계가 없다(온라인 결제를 붙일 때 더한다). */
public enum PaymentStatus {

	/** 받았다. 청구는 PAID다. 청구 하나에 하나뿐이다. */
	COMPLETED,

	/** 취소했다(환불은 데스크에서 따로). 청구는 다시 결제 전이다. 기록으로 남는다. */
	CANCELED

}
