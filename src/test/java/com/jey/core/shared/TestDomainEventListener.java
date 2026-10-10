package com.jey.core.shared;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

// 감사 로그처럼 개별 이벤트 타입을 모르고 공통 타입만 구독하는 리스너를 흉내 낸다. 테스트 전용.
// 이 빈은 프록시로 감싸지므로 상태는 필드가 아니라 메서드로 읽는다.
@Component
class TestDomainEventListener {

	private final List<DomainEvent> received = new CopyOnWriteArrayList<>();

	private final Set<UUID> failOnce = ConcurrentHashMap.newKeySet();

	@ApplicationModuleListener
	void on(DomainEvent event) {
		if (failOnce.remove(event.metadata().eventId())) {
			throw new IllegalStateException("테스트를 위해 일부러 낸 실패");
		}
		received.add(event);
	}

	List<DomainEvent> received() {
		return received;
	}

	// 이 ID의 이벤트는 처음 한 번 처리에 실패한다.
	void failOnceFor(UUID eventId) {
		failOnce.add(eventId);
	}

	boolean hasPendingFailure(UUID eventId) {
		return failOnce.contains(eventId);
	}

}
