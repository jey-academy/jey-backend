package com.jey.core.auth.api;

/**
 * 계정의 역할. 학부모·학생 등은 해당 기능을 만들 때 추가한다.
 */
public enum UserRole {

	/** 원장. 전체 지점과 설정. */
	ADMIN,

	/** 데스크 직원. 소속 지점의 업무. */
	STAFF,

	/** 연계 학원. 패스 발급. */
	PARTNER

}
