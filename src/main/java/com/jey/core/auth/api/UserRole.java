package com.jey.core.auth.api;

/**
 * 계정의 역할. 학부모·학생 등은 해당 기능을 만들 때 추가한다.
 *
 * <p>상수 이름은 DB 값, 권한 이름({@code ROLE_STAFF}), API 응답, 설정 키, 세션에 그대로 쓰인다.
 * 이름을 바꾸면 그 모두가 깨지므로 한 번 정한 이름은 바꾸지 않는다. 상수를 추가하는 것은 괜찮다.
 */
public enum UserRole {

	/** 원장. 전체 지점과 설정. */
	ADMIN,

	/** 데스크 직원. 소속 지점의 업무. */
	STAFF,

	/** 연계 학원. 패스 발급. */
	PARTNER;

	/** 한 지점에 소속되는 역할인지. 직원은 지점이 있어야 하고, 관리자와 연계 학원은 지점이 없다. */
	public boolean belongsToCampus() {
		return this == STAFF;
	}

	/**
	 * 역할과 소속 지점의 조합이 맞는지 확인한다.
	 * 지점 없는 직원을 허용하면 "지점이 없으면 전 지점"으로 해석하는 곳에서 전 지점 권한이 되어 버린다.
	 *
	 * @throws IllegalArgumentException 직원인데 지점이 없거나, 직원이 아닌데 지점이 있을 때
	 */
	public void validateCampus(Long campusId) {
		if (belongsToCampus() && campusId == null) {
			throw new IllegalArgumentException(this + " 계정은 소속 지점이 있어야 한다");
		}
		if (!belongsToCampus() && campusId != null) {
			throw new IllegalArgumentException(this + " 계정은 소속 지점을 가질 수 없다");
		}
	}

}
