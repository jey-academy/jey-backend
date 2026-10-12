package com.jey.core.auth.domain;

/**
 * 계정의 상태.
 *
 * <p>상수 이름은 DB 값, API 응답, 목록 조회의 조건 값에 그대로 쓰인다. 이름을 바꾸면 그 모두가 깨지므로 한 번 정한 이름은 바꾸지 않는다.
 */
public enum UserStatus {

	ACTIVE,

	/** 로그인할 수 없다. 퇴사한 직원, 계약이 끝난 연계 학원. */
	DISABLED

}
