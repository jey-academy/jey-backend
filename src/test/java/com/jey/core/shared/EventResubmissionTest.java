package com.jey.core.shared;

import java.time.Clock;
import java.time.Duration;

import com.jey.TestcontainersConfiguration;
import com.jey.core.audit.AuditLogService;
import com.jey.core.config.EventResubmission;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// 리스너가 실패한 이벤트는 발행 기록에 남기만 한다. 다시 보내는 작업이 돌아야 처리된다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class EventResubmissionTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	@Autowired
	EventResubmission resubmission;

	@Autowired
	ApplicationEventPublisher publisher;

	@Autowired
	TransactionTemplate transaction;

	@Autowired
	TestDomainEventListener listener;

	@Autowired
	AuditLogService auditLogService;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Clock clock;

	@Test
	void 처리에_실패한_이벤트는_다시_보내는_작업이_돌면_처리된다() {
		TestDomainEvent event = new TestDomainEvent(
				EventMetadata.create("core.resubmission-tested", 1, 1L, Actor.user(7), clock), 77);
		listener.failOnceFor(event.metadata().eventId());

		transaction.executeWithoutResult(status -> publisher.publishEvent(event));

		await().atMost(TIMEOUT)
				.untilAsserted(() -> assertThat(listener.hasPendingFailure(event.metadata().eventId())).isFalse());
		await().atMost(TIMEOUT).untilAsserted(() -> assertThat(incompleteCount(event)).isEqualTo(1));
		assertThat(listener.received()).doesNotContain(event);

		// 주기 작업이 하는 일을 바로 시킨다. 실제로는 발행된 지 일정 시간이 지난 것만 다시 보낸다.
		resubmission.resubmitOlderThan(Duration.ZERO);

		await().atMost(TIMEOUT).untilAsserted(() -> assertThat(listener.received()).contains(event));
		await().atMost(TIMEOUT).untilAsserted(() -> assertThat(incompleteCount(event)).isZero());
		// 다시 보내도 이미 처리한 리스너(감사 로그)의 기록은 한 줄이다.
		assertThat(auditLogService.search(null, "core.resubmission-tested", null, null, null, null, null, 0, 100)
				.getContent()).filteredOn(each -> event.metadata().eventId().toString().equals(each.getEventId()))
				.hasSize(1);
	}

	// 방금 발행돼 아직 처리 중인 이벤트까지 다시 보내면 같은 이벤트가 두 번 처리된다.
	@Test
	void 발행된_지_얼마_안_된_이벤트는_다시_보내지_않는다() {
		TestDomainEvent event = new TestDomainEvent(
				EventMetadata.create("core.resubmission-tested", 1, 1L, Actor.user(7), clock), 78);
		listener.failOnceFor(event.metadata().eventId());
		transaction.executeWithoutResult(status -> publisher.publishEvent(event));
		await().atMost(TIMEOUT).untilAsserted(() -> assertThat(incompleteCount(event)).isEqualTo(1));

		resubmission.resubmitOlderThan(Duration.ofMinutes(5));

		assertThat(incompleteCount(event)).isEqualTo(1);
		assertThat(listener.received()).doesNotContain(event);

		// 뒤의 테스트에 미완료 기록을 남기지 않는다.
		resubmission.resubmitOlderThan(Duration.ZERO);
		await().atMost(TIMEOUT).untilAsserted(() -> assertThat(incompleteCount(event)).isZero());
	}

	private int incompleteCount(TestDomainEvent event) {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM event_publication WHERE completion_date IS NULL AND serialized_event LIKE ?",
				Integer.class, "%" + event.metadata().eventId() + "%");
	}

}
