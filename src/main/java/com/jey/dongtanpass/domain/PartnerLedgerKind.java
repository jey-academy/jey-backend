package com.jey.dongtanpass.domain;

/** 연계 학원 장부에 쓰는 기록의 종류. 이름은 DB에 그대로 저장된다. 한 번 쓴 이름은 바꾸지 않는다. */
public enum PartnerLedgerKind {

	/** 그 학원이 JEY 발급 패스로 할인해 주었다(연계 학원의 사용 처리). 금액이 있다. */
	PARTNER_DISCOUNT,

	/** 그 학원이 발급한 패스가 JEY 지점에서 쓰였다(데스크의 사용 처리). */
	USED_AT_JEY

}
