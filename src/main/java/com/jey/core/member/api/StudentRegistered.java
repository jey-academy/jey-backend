package com.jey.core.member.api;

import com.jey.core.shared.DomainEvent;
import com.jey.core.shared.EntityRef;
import com.jey.core.shared.EventMetadata;

/**
 * 학생이 등록됐다. 봉투의 지점은 학생의 지점이고, 행위자는 등록한 계정이다.
 * 개인정보(이름, 전화번호)는 싣지 않는다. 필요한 쪽이 {@link MemberDirectory}로 조회한다.
 */
public record StudentRegistered(EventMetadata metadata, long studentId) implements DomainEvent {

	public static final String TYPE = "member.student-registered";

	public static final int VERSION = 1;

	@Override
	public EntityRef subject() {
		return EntityRef.of("student", studentId);
	}

}
