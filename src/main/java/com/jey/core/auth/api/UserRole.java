package com.jey.core.auth.api;

/**
 * 계정의 역할. 학부모·학생 등은 해당 기능을 만들 때 추가한다.
 *
 * <p>상수 이름은 DB 값, 권한 이름({@code ROLE_STAFF}), API 응답, 설정 키, 세션에 그대로 쓰인다.
 * 이름을 바꾸면 그 모두가 깨지므로 한 번 정한 이름은 바꾸지 않는다. 상수를 추가하는 것은 괜찮다.
 * 추가할 때는 어디에 속하는 역할인지를 함께 적어야 컴파일된다.
 */
public enum UserRole {

	/** 원장. 전체 지점과 설정. */
	ADMIN(Belongs.NOWHERE),

	/** 데스크 직원. 소속 지점의 업무. */
	STAFF(Belongs.CAMPUS),

	/** 연계 학원. 패스 발급. */
	PARTNER(Belongs.PARTNER);

	// 계정이 어디에 속하는가. 속한 곳의 식별자 하나만 가진다.
	private enum Belongs {

		NOWHERE, CAMPUS, PARTNER

	}

	private final Belongs belongs;

	UserRole(Belongs belongs) {
		this.belongs = belongs;
	}

	/** Spring Security의 권한 이름. 로그인할 때 이 이름으로 넣고, 접근 규칙을 확인할 때 이 이름으로 찾는다. */
	public String authority() {
		return "ROLE_" + name();
	}

	/** 한 지점에 소속되는 역할인지. 직원은 지점이 있어야 하고, 관리자와 연계 학원은 지점이 없다. */
	public boolean belongsToCampus() {
		return belongs == Belongs.CAMPUS;
	}

	/** 연계 학원에 속하는 역할인지. 연계 학원 계정은 식별자가 있어야 하고, 그 밖의 역할은 없어야 한다. */
	public boolean belongsToPartner() {
		return belongs == Belongs.PARTNER;
	}

	/**
	 * 역할과 소속(지점, 연계 학원 식별자)의 조합이 맞는지 한 번에 확인한다.
	 * 지점 없는 직원을 허용하면 "지점이 없으면 전 지점"으로 해석하는 곳에서 전 지점 권한이 되어 버리고,
	 * 식별자 없는 연계 학원 계정을 허용하면 "자기 발급분" 조건을 걸 수 없다.
	 *
	 * @throws IllegalArgumentException 있어야 할 식별자가 없거나, 없어야 할 식별자가 있을 때
	 */
	public void validateAffiliation(Long campusId, Long partnerId) {
		if (belongsToCampus() && campusId == null) {
			throw new IllegalArgumentException(this + " 계정은 소속 지점이 있어야 한다");
		}
		if (!belongsToCampus() && campusId != null) {
			throw new IllegalArgumentException(this + " 계정은 소속 지점을 가질 수 없다");
		}
		if (belongsToPartner() && partnerId == null) {
			throw new IllegalArgumentException(this + " 계정은 연계 학원 식별자가 있어야 한다");
		}
		if (!belongsToPartner() && partnerId != null) {
			throw new IllegalArgumentException(this + " 계정은 연계 학원 식별자를 가질 수 없다");
		}
	}

}
