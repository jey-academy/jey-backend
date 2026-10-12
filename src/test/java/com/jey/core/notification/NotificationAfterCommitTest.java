package com.jey.core.notification;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.jey.TestcontainersConfiguration;
import com.jey.core.notification.api.NotificationRequest;
import com.jey.core.notification.api.NotificationSender;
import com.jey.core.shared.Actor;
import com.jey.core.shared.EventMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// 알림은 일을 한 트랜잭션이 커밋된 뒤, 이벤트를 받은 리스너에서 맡긴다. 그 순서를 지키면 되돌려진 일의 알림이 나가지 않는다.
// 이 순서는 장치로 강제되지 않으므로(NotificationSender 문서), 지켰을 때의 결과를 본보기 리스너로 보인다 (ADR-0029).
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class NotificationAfterCommitTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	@Autowired
	ApplicationContext context;

	@Autowired
	ApplicationEventPublisher publisher;

	@Autowired
	TransactionTemplate transaction;

	@Autowired
	IncompleteEventPublications incompletePublications;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Clock clock;

	@Autowired
	TestNotifyingListener listener;

	@AfterEach
	void clearLogContext() {
		MDC.clear();
	}

	// 구현체가 둘이 되면 맡기는 쪽의 주입이 깨진다. 2차에 구현체를 바꿀 때는 하나만 뜨게 조건을 붙인다.
	@Test
	void 알림을_맡길_창구가_하나_있다() {
		assertThat(context.getBeansOfType(NotificationSender.class)).hasSize(1);
	}

	@Test
	void 일이_커밋되면_알림이_한_번_기록되고_그_줄에_요청_ID가_찍힌다(CapturedOutput output) {
		MDC.put("requestId", "request-that-notified-1");
		TestNotifyingEvent event = newEvent(61);

		transaction.executeWithoutResult(status -> publisher.publishEvent(event));

		await().atMost(TIMEOUT).untilAsserted(() -> assertThat(linesFor(output, event)).hasSize(1));
		assertThat(linesFor(output, event)).singleElement()
			.satisfies(line -> assertThat(line).contains("request-that-notified-1")
				.contains("type=notification.test-happened recipient=guardian:61 about=test-target:61 event="
						+ event.metadata().eventId()));
	}

	@Test
	void 일이_되돌려지면_알림이_기록되지_않는다(CapturedOutput output) {
		TestNotifyingEvent rolledBack = newEvent(62);
		transaction.executeWithoutResult(status -> {
			publisher.publishEvent(rolledBack);
			status.setRollbackOnly();
		});
		// 뒤에 낸 이벤트의 알림이 기록될 때까지 기다린다. 그때까지 앞의 것이 없으면 맡겨지지 않은 것이다.
		TestNotifyingEvent committed = newEvent(63);
		transaction.executeWithoutResult(status -> publisher.publishEvent(committed));

		await().atMost(TIMEOUT).untilAsserted(() -> assertThat(linesFor(output, committed)).hasSize(1));
		// 발행 기록도 같이 되돌려졌다. 기록이 없으면 리스너로 넘어갈 것도, 나중에 다시 실행될 것도 없다.
		assertThat(publications(rolledBack, false)).isZero();
		assertThat(linesFor(output, rolledBack)).isEmpty();
		assertThat(listener.requestedFor(rolledBack.metadata().eventId())).isEmpty();
	}

	// 리스너가 알림을 맡긴 뒤에 실패하면 같은 이벤트로 다시 돈다. 다시 맡겨진 요청은 앞의 것과 같은 알림이어야
	// 보내는 쪽이 가려낼 수 있다. 1차 구현체는 가려내지 않으므로 줄이 하나 더 남는다.
	// (주기적으로 다시 보내는 작업은 발행된 지 몇 분 지난 것만 집으므로 여기에 끼어들지 않는다.)
	@Test
	void 리스너가_다시_돌면_같은_알림을_다시_맡기고_그_뒤에는_끝난다(CapturedOutput output) {
		TestNotifyingEvent event = newEvent(64);
		UUID eventId = event.metadata().eventId();
		listener.failOnceAfterSendFor(eventId);
		transaction.executeWithoutResult(status -> publisher.publishEvent(event));
		// 이 이벤트를 받는 다른 리스너(감사 로그 등)는 끝나고, 실패한 이 리스너의 기록 하나만 남는다.
		await().atMost(TIMEOUT).untilAsserted(() -> {
			assertThat(listener.requestedFor(eventId)).hasSize(1);
			assertThat(publications(event, true)).isEqualTo(1);
		});

		incompletePublications.resubmitIncompletePublications(publication -> event.equals(publication.getEvent()));

		await().atMost(TIMEOUT).untilAsserted(() -> {
			assertThat(listener.requestedFor(eventId)).hasSize(2);
			assertThat(publications(event, true)).isZero();
		});
		// 끝난 뒤에 본다. 세 번째로 돌지 않았다.
		List<NotificationRequest> requests = listener.requestedFor(eventId);
		assertThat(requests).hasSize(2);
		assertThat(requests.get(1).isSameNotificationAs(requests.get(0))).isTrue();
		assertThat(linesFor(output, event)).hasSize(2);
	}

	private static List<String> linesFor(CapturedOutput output, TestNotifyingEvent event) {
		return output.getOut()
			.lines()
			.filter(line -> line.contains("알림 요청") && line.contains("event=" + event.metadata().eventId()))
			.toList();
	}

	// 이 이벤트의 발행 기록 수(리스너마다 하나). incompleteOnly면 아직 끝나지 않은 것만 센다.
	private int publications(TestNotifyingEvent event, boolean incompleteOnly) {
		String sql = "SELECT COUNT(*) FROM event_publication WHERE serialized_event LIKE ?"
				+ (incompleteOnly ? " AND completion_date IS NULL" : "");
		return jdbc.queryForObject(sql, Integer.class, "%" + event.metadata().eventId() + "%");
	}

	private TestNotifyingEvent newEvent(long targetId) {
		return new TestNotifyingEvent(EventMetadata.create(TestNotifyingEvent.TYPE, 1, 1L, Actor.user(7), clock),
				targetId);
	}

}
