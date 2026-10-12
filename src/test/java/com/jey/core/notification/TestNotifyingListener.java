package com.jey.core.notification;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.jey.core.notification.api.NotificationRequest;
import com.jey.core.notification.api.NotificationSender;
import com.jey.core.notification.api.Recipient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

// 모듈이 알림을 맡기는 모양의 본보기: 일을 한 트랜잭션이 커밋된 뒤, 이벤트를 받은 리스너에서 맡긴다. 테스트 전용.
// 이 빈은 프록시로 감싸지므로 상태는 필드가 아니라 메서드로 읽는다.
@Component
class TestNotifyingListener {

	private final NotificationSender sender;

	private final List<NotificationRequest> requested = new CopyOnWriteArrayList<>();

	private final Set<UUID> failOnceAfterSend = ConcurrentHashMap.newKeySet();

	TestNotifyingListener(NotificationSender sender) {
		this.sender = sender;
	}

	@ApplicationModuleListener
	void on(TestNotifyingEvent event) {
		NotificationRequest request = NotificationRequest.causedBy(event, TestNotificationType.TEST_HAPPENED,
				Recipient.guardian(event.targetId()));
		sender.send(request);
		requested.add(request);
		if (failOnceAfterSend.remove(event.metadata().eventId())) {
			throw new IllegalStateException("테스트를 위해 일부러 낸 실패");
		}
	}

	// 이 이벤트로 맡긴 요청들. 리스너가 다시 돌았으면 둘 이상이다.
	List<NotificationRequest> requestedFor(UUID eventId) {
		return requested.stream().filter(request -> request.causeEventId().equals(eventId)).toList();
	}

	// 이 ID의 이벤트는 알림을 맡긴 뒤에 처음 한 번 실패한다.
	void failOnceAfterSendFor(UUID eventId) {
		failOnceAfterSend.add(eventId);
	}

}
