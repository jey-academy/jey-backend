package com.jey.billing.domain;

/**
 * 청구의 상태. ISSUED →(결제) PAID →(결제 취소) ISSUED, ISSUED →(취소) CANCELED.
 * 열린 청구(한 학생 한 달에 하나)는 ISSUED와 PAID다.
 */
public enum InvoiceStatus {

	/** 만들었고 아직 결제되지 않았다. */
	ISSUED,

	/** 결제했다. 결제를 취소하면 ISSUED로 돌아간다. 바로 취소하지 못한다. */
	PAID,

	/** 취소했다. 닫힌 청구라 같은 달에 새 청구를 만들 수 있다. 다시 열지 않는다. */
	CANCELED

}
