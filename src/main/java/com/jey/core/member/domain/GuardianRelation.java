package com.jey.core.member.domain;

/** 학생에게 그 학부모가 누구인지. 대표 연락처는 {@link #MOTHER}가 먼저다({@link PrimaryContactRule}). */
public enum GuardianRelation {

	MOTHER,

	FATHER,

	GRANDPARENT,

	OTHER

}
