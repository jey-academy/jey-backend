package com.jey.core.member;

import com.jey.core.member.domain.Grade;

/** 학교 표시({@code 동탄고1}): 학교 이름 뒤에 학년 숫자. 장부의 학교 칸이 이 모양이다. */
final class SchoolLabels {

	private SchoolLabels() {
	}

	/**
	 * @return 학교가 없으면 {@code null}. 학년이 기타면 학교 이름만
	 */
	static String of(String school, Grade grade) {
		if (school == null) {
			return null;
		}
		if (grade == null || grade == Grade.ETC) {
			return school;
		}
		String name = grade.name();
		return school + name.substring(name.lastIndexOf('_') + 1);
	}

}
