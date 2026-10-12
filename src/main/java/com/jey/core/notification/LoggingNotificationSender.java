package com.jey.core.notification;

import com.jey.core.notification.api.NotificationRequest;
import com.jey.core.notification.api.NotificationSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 1차 구현체. 알림을 보내지 않고 맡겨진 것을 로그 한 줄로만 남긴다 (ADR-0029). 실제 발송(알림톡, 문자)은 2차에 들어온다.
 *
 * <p>같은 알림을 다시 맡겨도 걸러내지 않고 한 줄 더 남긴다. 걸러내려면 보낸 것을 기억할 곳이 있어야 하는데 그것은 2차의 발송 이력이다.
 * 남기는 것은 종류의 이름과 숫자 ID뿐이다({@link NotificationRequest#toString()}).
 */
@Component
class LoggingNotificationSender implements NotificationSender {

	private static final Logger log = LoggerFactory.getLogger(LoggingNotificationSender.class);

	@Override
	public void send(NotificationRequest request) {
		log.info("알림 요청(1차: 보내지 않고 기록만 한다): {}", request);
	}

}
