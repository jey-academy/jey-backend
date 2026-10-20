package com.jey.dongtanpass.api;

import java.util.Optional;

import com.jey.core.shared.DomainEvent;
import com.jey.core.shared.EntityRef;
import com.jey.core.shared.EventMetadata;

/**
 * JEY 지점이 다니는 학생에게 패스를 발급했다(연계 학원에서 쓴다). 봉투의 지점은 발급 지점이고, 행위자는 발급한 관리자다.
 * 개인정보(학생 이름, 학부모 번호)와 링크 토큰은 싣지 않는다.
 */
public record CampusPassIssued(EventMetadata metadata, long passId, long campusId, long studentId)
		implements DomainEvent {

	public static final String TYPE = "dongtanpass.campus-pass-issued";

	public static final int VERSION = 1;

	@Override
	public Optional<EntityRef> subject() {
		return Optional.of(PassRefs.pass(passId));
	}

}
