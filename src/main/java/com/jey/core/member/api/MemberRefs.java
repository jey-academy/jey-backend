package com.jey.core.member.api;

import com.jey.core.shared.EntityRef;

/**
 * 학생과 학부모를 가리키는 방법. 감사 로그의 대상과 이벤트의 대상이 같은 종류 이름을 쓰게 한다.
 * 종류 이름은 이미 쌓인 기록에 적혀 있고 조회 조건으로 쓰이므로 바꾸지 않는다.
 * 다른 모듈이 학생을 대상으로 적을 때도 여기서 만든다.
 */
public final class MemberRefs {

	public static final String STUDENT = "student";

	public static final String GUARDIAN = "guardian";

	private MemberRefs() {
	}

	public static EntityRef student(long studentId) {
		return EntityRef.of(STUDENT, studentId);
	}

	public static EntityRef guardian(long guardianId) {
		return EntityRef.of(GUARDIAN, guardianId);
	}

}
