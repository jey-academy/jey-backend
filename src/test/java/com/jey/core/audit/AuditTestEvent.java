package com.jey.core.audit;

import java.util.Optional;

import com.jey.core.shared.DomainEvent;
import com.jey.core.shared.EntityRef;
import com.jey.core.shared.EventMetadata;

// 다른 모듈이 내는 이벤트를 흉내 낸다. 감사 로그는 이 타입을 모르고 공통 봉투 타입으로만 받는다. 테스트 전용.
record AuditTestEvent(EventMetadata metadata, long passId, String note) implements DomainEvent {

	@Override
	public Optional<EntityRef> subject() {
		return Optional.empty();
	}

}
