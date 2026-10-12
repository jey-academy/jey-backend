package com.jey.core.notification;

import com.jey.core.notification.api.NotificationRequest;
import com.jey.core.notification.api.NotificationSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 1차 구현체. 알림을 보내지 않고 맡겨진 것을 로그 한 줄로만 남긴다 (ADR-0029). 실제 발송(알림톡, 문자)은 2차에 들어온다.
 * 여기에 맡겨진 알림은 나중에 발송 수단이 들어와도 나가지 않는다(이벤트는 끝난 것으로 적히고 남는 것은 이 로그 줄뿐이다).
 *
 * <p>같은 알림을 다시 맡겨도 걸러내지 않고 한 줄 더 남긴다. 걸러내려면 보낸 것을 기억할 곳이 있어야 하는데 그것은 2차의 발송 이력이다.
 * 남기는 것은 종류의 이름과 숫자 ID뿐이다({@link NotificationRequest#toString()}).
 */
@Component
class LoggingNotificationSender implements NotificationSender {

	private static final Logger log = LoggerFactory.getLogger(LoggingNotificationSender.class);

	// 이 줄이 맡겨진 알림의 유일한 기록이다. 로그 수준을 올려 이 줄이 꺼진 채로 뜨면 알림을 맡긴 것은 성공으로 끝나는데 기록은 없다.
	// 기동할 때의 설정만 본다. 떠 있는 동안 수준을 바꾸는 것까지는 막지 못한다.
	LoggingNotificationSender() {
		if (!log.isInfoEnabled()) {
			throw new IllegalStateException(
					"알림 요청을 기록할 수 없다. " + LoggingNotificationSender.class.getName() + "의 로그 수준이 INFO보다 높다");
		}
	}

	@Override
	public void send(NotificationRequest request) {
		log.info("알림 요청(1차: 보내지 않고 기록만 한다): {}", request);
	}

}
