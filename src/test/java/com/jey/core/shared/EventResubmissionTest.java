package com.jey.core.shared;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import com.jey.TestcontainersConfiguration;
import com.jey.core.config.EventResubmission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.EventPublication;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.FixedDelayTask;
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
	ApplicationContext context;

	@Autowired
	ScheduledAnnotationBeanPostProcessor scheduled;

	@Autowired
	ApplicationEventPublisher publisher;

	@Autowired
	TransactionTemplate transaction;

	@Autowired
	TestDomainEventListener listener;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Clock clock;

	@Value("${spring.modulith.events.staleness.resubmitted}")
	Duration resubmittedStaleness;

	// 테스트가 중간에 실패해도 처리되지 않은 이벤트를 남기지 않는다. 남으면 그것을 기다리는 다른 테스트까지 같이 실패한다.
	@AfterEach
	void drain() {
		jdbc.update("UPDATE event_publication SET status = 'FAILED' WHERE completion_date IS NULL "
				+ "AND status = 'RESUBMITTED' AND serialized_event LIKE '%core.resubmission-tested%'");
		resubmission.resubmitOlderThan(Duration.ZERO);
		await().atMost(TIMEOUT)
				.untilAsserted(() -> assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_publication "
						+ "WHERE completion_date IS NULL AND serialized_event LIKE '%core.resubmission-tested%'",
						Integer.class)).isZero());
	}

	@Test
	void 처리에_실패한_이벤트는_다시_보내는_작업이_돌면_처리된다() {
		TestDomainEvent event = publishFailingOnce(77);

		// 주기 작업이 하는 일을 바로 시킨다. 실제로는 발행된 지 일정 시간이 지난 것만 다시 보낸다.
		resubmission.resubmitOlderThan(Duration.ZERO);

		await().atMost(TIMEOUT).untilAsserted(() -> assertThat(listener.received()).contains(event));
		await().atMost(TIMEOUT).untilAsserted(() -> assertThat(incompleteCount(event)).isZero());
	}

	// 방금 발행돼 처음 처리되는 중인 이벤트까지 다시 보내면 같은 이벤트가 두 번 처리된다.
	@Test
	void 발행된_지_얼마_안_된_이벤트는_다시_보내지_않는다() {
		TestDomainEvent event = publishFailingOnce(78);

		resubmission.resubmitOlderThan(Duration.ofMinutes(5));

		// 다시 보내졌다면 리스너가 다른 스레드에서 곧 받는다. 얼마간 지켜봐도 그대로여야 한다.
		await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(incompleteCount(event)).isEqualTo(1);
			assertThat(listener.received()).doesNotContain(event);
		});
	}

	// 다시 보낼 때 발행 기록에 "다시 보내는 중" 표시가 먼저 붙고, 그 표시가 붙은 것은 다시 보내지 않는다.
	// 표시가 붙은 뒤 리스너가 시작하기 전에 서버가 내려가면 표시가 남는다. 오래된 표시를 실패로 되돌려야 다시 보내진다.
	@Test
	void 다시_보내는_중에_끊긴_이벤트는_표시를_되돌린_뒤에야_다시_보내진다() {
		TestDomainEvent event = publishFailingOnce(79);
		// 표시만 붙고 리스너는 돌지 못한 상태를 만든다. 표시는 방금 붙었고 발행은 오래전이다.
		// 감시는 발행 시각에서 잰다. 마지막으로 다시 보낸 시각에서 잰다면 이 이벤트는 풀리지 않는다.
		jdbc.update("UPDATE event_publication SET status = 'RESUBMITTED', last_resubmission_date = ?, "
				+ "publication_date = ? WHERE completion_date IS NULL AND serialized_event LIKE ?",
				java.sql.Timestamp.from(clock.instant()), "2026-01-01 00:00:00",
				"%" + event.metadata().eventId() + "%");

		resubmission.resubmitOlderThan(Duration.ZERO);

		await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(incompleteCount(event)).isEqualTo(1);
			assertThat(listener.received()).doesNotContain(event);
		});

		// 설정(spring.modulith.events.staleness.resubmitted)이 켜져 있으면 라이브러리가 주기적으로 하는 일이다.
		markStalePublicationsFailedAsConfigured();
		resubmission.resubmitOlderThan(Duration.ZERO);

		await().atMost(TIMEOUT).untilAsserted(() -> assertThat(listener.received()).contains(event));
		await().atMost(TIMEOUT).untilAsserted(() -> assertThat(incompleteCount(event)).isZero());
	}

	// 위의 되돌리기는 설정이 있어야 돈다. 기본값은 꺼짐이라, 설정이 빠지면 끊긴 이벤트는 영영 다시 보내지지 않는다.
	// 설정 파일의 글자가 아니라 라이브러리가 실제로 읽은 값을 본다. 설정 이름이 틀리면 여기서 0초로 나온다.
	// 셋을 다 적어야 한다. 하나만 적으면 감시가 켜지면서 나머지는 0초가 되어, 방금 발행됐거나 처리 중인 이벤트까지 실패로 표시된다.
	@Test
	void 오래된_상태를_실패로_되돌리는_기준이_세_상태_모두에_정해져_있다() {
		assertThat(configuredStaleness(EventPublication.Status.RESUBMITTED)).isEqualTo(Duration.ofMinutes(10));
		assertThat(configuredStaleness(EventPublication.Status.PROCESSING)).isEqualTo(Duration.ofMinutes(10));
		assertThat(configuredStaleness(EventPublication.Status.PUBLISHED)).isEqualTo(Duration.ofMinutes(10));
		assertThat(resubmittedStaleness).isEqualTo(Duration.ofMinutes(10));
	}

	// 방금 발행돼 아직 끝나지 않은 이벤트는 감시가 건드리지 않는다. 건드리면 실패한 적 없는 이벤트가 실패로 보인다.
	@Test
	void 감시는_발행된_지_얼마_안_된_이벤트의_상태를_바꾸지_않는다() {
		TestDomainEvent event = publishFailingOnce(80);
		jdbc.update("UPDATE event_publication SET status = 'PROCESSING' WHERE completion_date IS NULL "
				+ "AND serialized_event LIKE ?", "%" + event.metadata().eventId() + "%");

		markStalePublicationsFailedAsConfigured();

		assertThat(jdbc.queryForObject("SELECT status FROM event_publication WHERE completion_date IS NULL "
				+ "AND serialized_event LIKE ?", String.class, "%" + event.metadata().eventId() + "%"))
				.isEqualTo("PROCESSING");
	}

	// 애너테이션 하나가 빠지면 다시 보내는 일이 통째로 사라지는데, 직접 불러 보는 테스트는 그래도 통과한다.
	@Test
	void 다시_보내는_작업이_주기_작업으로_등록돼_있다() {
		assertThat(scheduled.getScheduledTasks()).map(each -> each.getTask())
				.filteredOn(task -> task.toString().contains("EventResubmission.resubmitStale"))
				.singleElement()
				.isInstanceOfSatisfying(FixedDelayTask.class,
						task -> assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofMinutes(1)));
	}

	// 라이브러리의 감시가 주기적으로 하는 일을 바로 시킨다. 기준은 운영 설정에서 읽힌 것을 그대로 쓴다.
	// 그 타입들은 실행할 때만 있는 라이브러리 안쪽 것이라 이름으로 찾아 부른다.
	private void markStalePublicationsFailedAsConfigured() {
		try {
			Class<?> registryType = Class.forName("org.springframework.modulith.events.core.EventPublicationRegistry");
			Class<?> stalenessType = Class.forName("org.springframework.modulith.events.core.Staleness");
			registryType.getMethod("markStalePublicationsFailed", stalenessType)
					.invoke(context.getBean(registryType), context.getBean(stalenessType));
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("발행 기록 저장소를 부르지 못했다", ex);
		}
	}

	// 라이브러리가 설정에서 읽은 기준. 설정 이름이 바뀌거나 빠지면 0초가 된다.
	private Duration configuredStaleness(EventPublication.Status status) {
		try {
			Class<?> stalenessType = Class.forName("org.springframework.modulith.events.core.Staleness");
			return (Duration) stalenessType.getMethod("getStaleness", EventPublication.Status.class)
					.invoke(context.getBean(stalenessType), status);
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("감시 설정을 읽지 못했다", ex);
		}
	}

	// 처리에 한 번 실패해서 발행 기록에 미완료로 남은 이벤트.
	private TestDomainEvent publishFailingOnce(long targetId) {
		TestDomainEvent event = new TestDomainEvent(
				EventMetadata.create("core.resubmission-tested", 1, 1L, Actor.user(7), clock), targetId);
		UUID eventId = event.metadata().eventId();
		listener.failOnceFor(eventId);
		transaction.executeWithoutResult(status -> publisher.publishEvent(event));
		await().atMost(TIMEOUT).untilAsserted(() -> assertThat(listener.hasPendingFailure(eventId)).isFalse());
		await().atMost(TIMEOUT).untilAsserted(() -> assertThat(incompleteCount(event)).isEqualTo(1));
		assertThat(listener.received()).doesNotContain(event);
		return event;
	}

	private int incompleteCount(TestDomainEvent event) {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM event_publication WHERE completion_date IS NULL AND serialized_event LIKE ?",
				Integer.class, "%" + event.metadata().eventId() + "%");
	}

}
