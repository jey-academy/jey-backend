package com.jey.core.notification.api;

/**
 * 알림을 맡기는 창구. 맡기는 쪽은 어떻게 보내는지(알림톡, 문자, 기록만)를 모른다.
 *
 * <p><b>언제 부르는가</b>: 그 일을 한 트랜잭션이 커밋된 뒤, 도메인 이벤트를 받은 리스너({@code @ApplicationModuleListener}) 안에서 부른다.
 * 일을 하는 트랜잭션 안에서 부르면, 그 트랜잭션이 되돌려져도 알림은 이미 맡겨진 뒤다(없던 일의 알림이 나간다).
 * 이 규칙은 장치로 강제되지 않는다. 리스너도 자기 트랜잭션을 열기 때문에 "트랜잭션 안인가"로는 가릴 수 없다.
 *
 * <p><b>다시 맡겨도 된다</b>: 리스너는 실패하면 같은 이벤트로 다시 돈다. 같은 알림({@link NotificationRequest#isSameNotificationAs})을
 * 다시 맡기는 것은 정상이고, 한 번만 보내는 것은 구현체의 몫이다. 맡기는 쪽이 "이미 맡겼는지"를 따로 기억하지 않는다.
 */
public interface NotificationSender {

	/**
	 * 알림을 맡긴다. 돌아왔다고 해서 받는 사람에게 닿은 것은 아니다.
	 *
	 * @throws RuntimeException 맡기지 못했을 때. 리스너가 그대로 던지면 이벤트가 발행 기록에 남아 다시 실행된다
	 */
	void send(NotificationRequest request);

}
