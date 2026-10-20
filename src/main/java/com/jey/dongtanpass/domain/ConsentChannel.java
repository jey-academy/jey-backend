package com.jey.dongtanpass.domain;

/** 동의를 어떻게 받았는지. 발급한 쪽이 고지하고 확인한다. 학부모가 직접 동의하는 길이 생기면 더한다. */
public enum ConsentChannel {

	/** 연계 학원이 고지하고 확인했다(연계 학원 발급). */
	PARTNER_CONFIRMED,

	/** JEY 관리자가 고지하고 확인했다(JEY 발급). */
	ADMIN_CONFIRMED

}
