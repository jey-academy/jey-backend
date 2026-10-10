package com.jey.core.shared;

import java.time.Clock;
import java.time.Duration;

import com.jey.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class DomainEventPublicationTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

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
	TestDomainEventListener listener;

	@Test
	void 봉투를_구현한_이벤트는_공통_타입으로_구독된다() {
		TestDomainEvent event = newEvent(42);

		publish(event);

		await().atMost(TIMEOUT).untilAsserted(() -> assertThat(listener.received()).contains(event));
		await().atMost(TIMEOUT).untilAsserted(() -> assertThat(incompleteCount(event)).isZero());
	}

	// 리스너가 실패해도 이벤트는 사라지지 않고 발행 기록에 남는다.
	// 다시 보내면 기록에서 되살린 이벤트가 원래와 같은 내용(같은 eventId)으로 도착해야 멱등 처리가 가능하다.
	@Test
	void 처리에_실패한_이벤트는_기록에_남고_다시_보내면_같은_내용으로_도착한다() {
		TestDomainEvent event = newEvent(43);
		listener.failOnceFor(event.metadata().eventId());

		publish(event);

		await().atMost(TIMEOUT)
				.untilAsserted(() -> assertThat(listener.hasPendingFailure(event.metadata().eventId())).isFalse());
		await().atMost(TIMEOUT).untilAsserted(() -> assertThat(incompleteCount(event)).isEqualTo(1));
		assertThat(listener.received()).doesNotContain(event);

		incompletePublications.resubmitIncompletePublications(publication -> event.equals(publication.getEvent()));

		await().atMost(TIMEOUT).untilAsserted(() -> assertThat(listener.received()).contains(event));
		await().atMost(TIMEOUT).untilAsserted(() -> assertThat(incompleteCount(event)).isZero());
	}

	private TestDomainEvent newEvent(long targetId) {
		return new TestDomainEvent(EventMetadata.create("core.test-happened", 1, 1L, clock), targetId);
	}

	// 리스너는 트랜잭션이 커밋된 뒤에 실행되므로 트랜잭션 안에서 발행한다.
	private void publish(TestDomainEvent event) {
		transaction.executeWithoutResult(status -> publisher.publishEvent(event));
	}

	private int incompleteCount(TestDomainEvent event) {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM event_publication WHERE completion_date IS NULL AND serialized_event LIKE ?",
				Integer.class, "%" + event.metadata().eventId() + "%");
	}

}
