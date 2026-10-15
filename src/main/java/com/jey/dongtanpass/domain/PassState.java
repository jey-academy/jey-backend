package com.jey.dongtanpass.domain;

/** 화면에 보일 상태. 저장 상태에 만료를 더한 것이다({@link Pass#stateOn}). */
public enum PassState {

	/** 쓸 수 있다(만료일 당일까지). */
	ISSUED("발급됨"),

	/** 쓰지 않은 채 만료일이 지났다. */
	EXPIRED("만료"),

	REDEEMED("사용됨"),

	CANCELED("취소됨");

	private final String label;

	PassState(String label) {
		this.label = label;
	}

	/** 장부 CSV에 쓰는 한국어 표시. */
	public String label() {
		return label;
	}

}
