package com.jey.core.member.domain;

/**
 * 학년. 자유 입력을 받으면 "고1", "고등1", "1학년"이 섞여서 정해진 값만 쓴다.
 * 해가 바뀌어도 자동으로 올라가지 않는다. 직원이 고친다.
 */
public enum Grade {

	ELEM_1, ELEM_2, ELEM_3, ELEM_4, ELEM_5, ELEM_6,

	MIDDLE_1, MIDDLE_2, MIDDLE_3,

	HIGH_1, HIGH_2, HIGH_3,

	// 미취학, 졸업생 등
	ETC

}
