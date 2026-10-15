package com.jey.dongtanpass.domain;

/** 연계 학원 데스크에서 받은 결제의 수단. 결제는 앱이 하지 않고 기록만 한다(ADR-0001). */
public enum PaymentMethod {

	CARD("카드"),

	CASH("현금"),

	TRANSFER("계좌이체"),

	OTHER("기타");

	private final String label;

	PaymentMethod(String label) {
		this.label = label;
	}

	/** 장부 CSV에 쓰는 한국어 표시. */
	public String label() {
		return label;
	}

}
