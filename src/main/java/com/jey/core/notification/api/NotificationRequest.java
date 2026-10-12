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
 * <p>알림은 도메인 이벤트에서만 나온다. {@link #causedBy}로 만들고 생성자는 직접 부르지 않는다({@code NotificationTypeRulesTest}가 막는다).
 * 그 이벤트의 ID가 같은 알림인지 가리는 값이 된다({@link #isSameNotificationAs}). 아무 ID나 넣으면 다시 맡긴 알림을 가려낼 수 없다.
 *
 * @param type 알림의 종류. enum으로 선언한 것만 받는다
 * @param recipient 받는 사람
 * @param about 무엇에 대한 알림인지(예: 패스 5번). 그 대상을 가진 모듈의 {@code api}가 만든 값을 쓴다
 * @param causeEventId 이 알림을 일으킨 도메인 이벤트의 ID
 */
public record NotificationRequest(NotificationType type, Recipient recipient, EntityRef about, UUID causeEventId) {

	/**
	 * @throws IllegalArgumentException 종류가 enum이 아니거나 그 이름이 형식에 맞지 않을 때
	 */
	public NotificationRequest {
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(recipient, "recipient");
		Objects.requireNonNull(about, "about");
		Objects.requireNonNull(causeEventId, "causeEventId");
		// 람다나 그때그때 만든 객체는 이름을 실행 중에 지어낼 수 있다(자유 문자열 칸이 된다). 규칙 테스트도 그런 종류는 보지 못한다.
		if (!(type instanceof Enum<?>)) {
			throw new IllegalArgumentException("알림 종류는 enum으로 선언한다: " + type.getClass().getName());
		}
		if (!EventMetadata.isValidName(type.code())) {
			throw new IllegalArgumentException("알림 종류의 이름은 {모듈}.{사건} 형식의 소문자 kebab-case여야 한다: " + type.code());
		}
	}

	/**
	 * 이벤트를 받은 리스너가 알림을 만들 때 쓴다. 대상은 이벤트의 대상({@link DomainEvent#subject()})이다.
	 *
	 * <p>여기서 나는 예외는 코드가 틀린 것이라 다시 실행해도 낫지 않는다. 리스너가 그대로 던지면 그 이벤트는 고칠 때까지 계속 다시 실행된다.
	 * 그래서 알림을 맡기는 리스너마다 실제 이벤트로 여기까지 도는 테스트를 둔다.
	 *
	 * @throws IllegalArgumentException 대상이 없는 이벤트일 때. 무엇에 대한 알림인지 모르면 보내는 쪽이 문구에 넣을 값을 찾을 수 없다.
	 * 종류가 enum이 아니거나 그 이름이 형식에 맞지 않을 때도 같다
	 */
	public static NotificationRequest causedBy(DomainEvent event, NotificationType type, Recipient recipient) {
		EventMetadata metadata = event.metadata();
		EntityRef about = event.subject()
			.orElseThrow(() -> new IllegalArgumentException("대상이 없는 이벤트로는 알림을 만들 수 없다: " + metadata.eventType()));
		return new NotificationRequest(type, recipient, about, metadata.eventId());
	}

	/**
	 * 같은 알림을 다시 맡긴 것인지. 종류의 이름, 받는 사람, 일으킨 이벤트가 같으면 같은 알림이다.
	 * 대상은 보지 않는다(대상은 이벤트가 정한다).
	 *
	 * <p>실패한 이벤트는 같은 ID로 다시 오고 리스너가 다시 돈다. 그때 다시 맡겨진 요청을 보내는 쪽이 이것으로 가려낸다.
	 *
	 * <p>{@link #causedBy}로 만든 요청끼리는 {@code equals}와 같은 답을 낸다. 요청을 저장하거나 다른 프로세스로 넘긴 뒤에 가릴 때는
	 * 이 세 값(종류의 이름, 받는 사람, 이벤트 ID)을 키로 쓴다. 종류는 그때 이름으로만 남는다.
	 */
	public boolean isSameNotificationAs(NotificationRequest other) {
		return type.code().equals(other.type.code()) && recipient.equals(other.recipient)
				&& causeEventId.equals(other.causeEventId);
	}

	// 사람이 읽는 로그용 모양. 종류의 이름과 숫자 ID뿐이다. 같은 알림을 가리는 키나 메시지의 값으로 쓰지 않는다.
	@Override
	public String toString() {
		return "type=" + type.code() + " recipient=" + recipient + " about=" + about.type() + ":" + about.id() + " event="
				+ causeEventId;
	}

}
