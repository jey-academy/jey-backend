package com.jey.core.notification;

import java.util.Optional;

import com.jey.core.shared.DomainEvent;
import com.jey.core.shared.EntityRef;
import com.jey.core.shared.EventMetadata;

// 알림을 일으키는 사건의 본보기. 대상이 있는 이벤트다(대상이 없으면 알림을 만들 수 없다). 테스트 전용.
record TestNotifyingEvent(EventMetadata metadata, long targetId) implements DomainEvent {

	static final String TYPE = "notification.test-happened";

	@Override
	public Optional<EntityRef> subject() {
		return Optional.of(EntityRef.of("test-target", targetId));
	}

}
