package com.jey.core.notification.api;

import java.util.Objects;
import java.util.UUID;

import com.jey.core.shared.DomainEvent;
import com.jey.core.shared.EntityRef;
import com.jey.core.shared.EventMetadata;

/**
 * 알림 요청 하나. 무엇을(종류), 누구에게(받는 사람), 무엇에 대해(대상) 알릴지를 ID로만 적는다.
 *
 * <p>문구도 개인정보도 싣지 않는다. 글자를 자유롭게 적는 칸이 없어서 실을 자리가 없다.
 * 문구에 들어갈 값(이름, 금액)과 보낼 곳(전화번호)은 실제로 보내는 쪽이 대상과 받는 사람의 ID로 조회한다.
 *
 * <p>알림은 도메인 이벤트에서만 나온다. 그 이벤트의 ID가 같은 알림인지 가리는 값이 된다({@link #isSameNotificationAs}).
 *
 * @param type 알림의 종류
 * @param recipient 받는 사람
 * @param about 무엇에 대한 알림인지(예: 패스 5번). 그 대상을 가진 모듈의 {@code api}가 만든 값을 쓴다
 * @param causeEventId 이 알림을 일으킨 도메인 이벤트의 ID
 */
public record NotificationRequest(NotificationType type, Recipient recipient, EntityRef about, UUID causeEventId) {

	/**
	 * @throws IllegalArgumentException 종류의 이름이 형식에 맞지 않을 때
	 */
	public NotificationRequest {
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(recipient, "recipient");
		Objects.requireNonNull(about, "about");
		Objects.requireNonNull(causeEventId, "causeEventId");
		if (!EventMetadata.isValidName(type.code())) {
			throw new IllegalArgumentException("알림 종류의 이름은 {모듈}.{사건} 형식의 소문자 kebab-case여야 한다: " + type.code());
		}
	}

	/**
	 * 이벤트를 받은 리스너가 알림을 만들 때 쓴다. 대상은 이벤트의 대상({@link DomainEvent#subject()})이다.
	 *
	 * @throws IllegalArgumentException 대상이 없는 이벤트일 때. 무엇에 대한 알림인지 모르면 보내는 쪽이 문구에 넣을 값을 찾을 수 없다
	 */
	public static NotificationRequest causedBy(DomainEvent event, NotificationType type, Recipient recipient) {
		EventMetadata metadata = event.metadata();
		EntityRef about = event.subject()
			.orElseThrow(() -> new IllegalArgumentException("대상이 없는 이벤트로는 알림을 만들 수 없다: " + metadata.eventType()));
		return new NotificationRequest(type, recipient, about, metadata.eventId());
	}

	/**
	 * 같은 알림을 다시 맡긴 것인지. 종류의 이름, 받는 사람, 일으킨 이벤트가 같으면 같은 알림이다.
	 *
	 * <p>실패한 이벤트는 같은 ID로 다시 오고 리스너가 다시 돈다. 그때 다시 맡겨진 요청을 보내는 쪽이 이것으로 가려낸다.
	 */
	public boolean isSameNotificationAs(NotificationRequest other) {
		return type.code().equals(other.type.code()) && recipient.equals(other.recipient)
				&& causeEventId.equals(other.causeEventId);
	}

	// 로그에 그대로 찍을 수 있는 모양. 종류의 이름과 숫자 ID뿐이다.
	@Override
	public String toString() {
		return "type=" + type.code() + " recipient=" + recipient + " about=" + about.type() + ":" + about.id() + " event="
				+ causeEventId;
	}

}
