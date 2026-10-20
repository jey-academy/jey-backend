package com.jey.billing.domain;

/** 청구의 상태. 결제와 취소는 결제 기록을 만들 때 더한다. */
public enum InvoiceStatus {

	/** 만들었고 아직 결제되지 않았다. 열린 청구다(한 학생 한 달에 하나). */
	ISSUED

}
