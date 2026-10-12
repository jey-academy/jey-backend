package com.jey.core.notification;

import java.util.UUID;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jey.core.notification.api.NotificationRequest;
import com.jey.core.notification.api.Recipient;
import com.jey.core.shared.EntityRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingNotificationSenderTest {

	private static final UUID EVENT_ID = UUID.fromString("01928f3e-7c1a-7000-8000-000000000001");

	private final Logger logger = (Logger) LoggerFactory.getLogger(LoggingNotificationSender.class);

	private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

	private final LoggingNotificationSender sender = new LoggingNotificationSender();

	@BeforeEach
	void attach() {
		logged.start();
		logger.addAppender(logged);
	}

	@AfterEach
	void detach() {
		logger.detachAppender(logged);
	}

	// 줄 전체를 비교한다. 종류의 이름과 숫자 ID 말고는 아무것도 없다는 것까지 본다.
	@Test
	void 맡긴_알림을_종류와_ID만으로_한_줄_적는다() {
		sender.send(request(Recipient.guardian(12)));

		assertThat(logged.list).singleElement().satisfies(line -> {
			assertThat(line.getLevel()).isEqualTo(Level.INFO);
			assertThat(line.getFormattedMessage())
				.isEqualTo("알림 요청(1차: 보내지 않고 기록만 한다): type=notification.test-happened recipient=guardian:12"
						+ " about=test-target:5 event=01928f3e-7c1a-7000-8000-000000000001");
		});
	}

	@Test
	void 관리자_전체에게_가는_알림은_받는_사람을_admins로_적는다() {
		sender.send(request(Recipient.admins()));

		assertThat(logged.list).singleElement()
			.satisfies(line -> assertThat(line.getFormattedMessage()).contains(" recipient=admins "));
	}

	// 1차 구현체는 걸러내지 않는다. 걸러내려면 보낸 것을 기억할 곳(2차의 발송 이력)이 있어야 한다.
	@Test
	void 같은_알림을_다시_맡기면_한_줄_더_적는다() {
		sender.send(request(Recipient.guardian(12)));
		sender.send(request(Recipient.guardian(12)));

		assertThat(logged.list).hasSize(2);
		assertThat(logged.list.get(0).getFormattedMessage()).isEqualTo(logged.list.get(1).getFormattedMessage());
	}

	private static NotificationRequest request(Recipient recipient) {
		return new NotificationRequest(TestNotificationType.TEST_HAPPENED, recipient, EntityRef.of("test-target", 5),
				EVENT_ID);
	}

}
