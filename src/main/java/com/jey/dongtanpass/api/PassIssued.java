package com.jey.dongtanpass.api;

import java.util.Optional;

import com.jey.core.shared.DomainEvent;
import com.jey.core.shared.EntityRef;
import com.jey.core.shared.EventMetadata;

/**
 * 패스가 발급됐다. 봉투의 지점은 없고(패스는 지점과 무관하다), 행위자는 발급한 연계 학원 계정이다.
 * 개인정보(학생 이름, 학부모 번호)와 링크 토큰은 싣지 않는다.
 */
public record PassIssued(EventMetadata metadata, long passId, long partnerId) implements DomainEvent {

	public static final String TYPE = "dongtanpass.pass-issued";

	public static final int VERSION = 1;

	@Override
	public Optional<EntityRef> subject() {
		return Optional.of(PassRefs.pass(passId));
	}

}
