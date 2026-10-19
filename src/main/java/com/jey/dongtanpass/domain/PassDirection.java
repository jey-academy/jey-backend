package com.jey.dongtanpass.domain;

/**
 * 패스가 어느 쪽에서 발급돼 어느 쪽에서 쓰이는가.
 */
public enum PassDirection {

	/** 연계 학원이 발급하고 JEY 지점이 할인한다(ADR-0001). */
	PARTNER_ISSUED,

	/** JEY 지점이 다니는 학생에게 발급하고 연계 학원이 할인한다. */
	CAMPUS_ISSUED

}
