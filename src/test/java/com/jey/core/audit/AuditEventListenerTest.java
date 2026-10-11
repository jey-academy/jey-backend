package com.jey.core.audit;

import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.jey.TestcontainersConfiguration;
import com.jey.core.audit.api.AuditEntry;
import com.jey.core.audit.api.AuditLog;
import com.jey.core.audit.api.AuditOutcome;
import com.jey.core.audit.domain.AuditRecord;
import com.jey.core.audit.domain.AuditRecordRepository;
import com.jey.core.auth.api.CurrentActor;
import com.jey.core.shared.Actor;
import com.jey.core.shared.DomainEvent;
import com.jey.core.shared.EntityRef;
import com.jey.core.shared.EventMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class AuditEventListenerTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private static final int THREADS = 8;

	@Autowired
	ApplicationEventPublisher publisher;

	@Autowired
	TransactionTemplate transaction;

	@Autowired
	AuditLog auditLog;

	@Autowired
	AuditLogService auditLogService;

	@Autowired
	AuditRecordRepository records;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	JsonMapper jsonMapper;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	CurrentActor currentActor;

	@Autowired
	Clock clock;

	// 감사 로그는 이 이벤트 타입을 모른다. 공통 봉투 타입으로 받아서 봉투의 값과 내용을 적는다.
	@Test
	void 발행된_이벤트가_한_줄로_기록된다() {
		AuditTestEvent event = newEvent(42, "메모");

		transaction.executeWithoutResult(status -> publisher.publishEvent(event));

		await().atMost(TIMEOUT).untilAsserted(() -> assertThat(find(event)).hasSize(1));
		AuditRecord saved = find(event).getFirst();
		assertThat(saved.getAction()).isEqualTo(event.metadata().eventType());
		assertThat(saved.getOutcome()).isEqualTo(AuditOutcome.DONE);
		assertThat(saved.getActorId()).isEqualTo(7L);
		assertThat(saved.getCampusId()).isEqualTo(2L);
		// 적힌 시각이 아니라 일어난 시각이다.
		assertThat(saved.getOccurredAt()).isEqualTo(event.metadata().occurredAt().truncatedTo(ChronoUnit.MICROS));
		assertThat(saved.getCreatedAt()).isAfterOrEqualTo(saved.getOccurredAt());
		assertThat(saved.getEventId()).isEqualTo(event.metadata().eventId().toString());
		// 봉투는 컬럼으로 옮겼으므로 상세에는 내용만 있다.
		assertThat(saved.getDetailJson()).isEqualTo("{\"passId\":42,\"note\":\"메모\"}");
		assertThat(saved.getTargetType()).isNull();
		// 리스너는 요청 밖에서 돈다. 저장한 요청의 계정은 없다.
		assertThat(saved.getCreatedBy()).isNull();
		await().atMost(TIMEOUT).untilAsserted(() -> assertThat(incompleteAuditPublications(event)).isZero());
	}

	// 이벤트가 무엇에 대한 것인지 알려 주면 대상으로 적힌다. "그 학생에게 있었던 일"로 찾을 수 있다.
	// 대상을 알려 주는 메서드는 JSON에 실리지 않는다. 실리면 발행 기록과 상세에 같은 값이 두 번 들어간다.
	@Test
	void 이벤트가_알려_준_대상이_기록의_대상이_된다() {
		SubjectEvent event = new SubjectEvent(
				EventMetadata.create(TestAuditAction.newCode(), 1, 1L, Actor.user(7), clock), 31);

		auditLogService.recordEvent(event);

		assertThat(auditLogService.search(null, event.metadata().eventType(), null, "pass", 31L, null, null, 0, 10)
				.getContent()).singleElement()
				.satisfies(saved -> assertThat(saved.getDetailJson()).isEqualTo("{\"passId\":31}"));
		assertThat(jsonMapper.writeValueAsString(event)).doesNotContain("subject");
	}

	// 리스너는 실패하면 다시 실행된다. 저장한 뒤 완료 표시 전에 죽으면 같은 이벤트가 또 온다.
	@Test
	void 같은_이벤트가_다시_와도_한_줄이고_실패하지_않는다() {
		AuditTestEvent event = newEvent(1, null);

		auditLogService.recordEvent(event);
		auditLogService.recordEvent(event);

		assertThat(find(event)).hasSize(1);
	}

	// 실제 리스너는 자기 트랜잭션 안에서 돈다. 이미 적힌 이벤트를 만나도 그 트랜잭션은 정상으로 끝나야 한다.
	// 끝나지 못하면 그 이벤트는 처리에 실패한 것으로 남아 끝없이 다시 온다.
	@Test
	void 트랜잭션_안에서_이미_적힌_이벤트를_만나도_그_트랜잭션은_정상으로_끝난다() {
		AuditTestEvent event = newEvent(1, null);
		auditLogService.recordEvent(event);
		TestAuditAction sameTransaction = TestAuditAction.done();

		transaction.executeWithoutResult(status -> {
			auditLogService.recordEvent(event);
			auditLogService.recordEvent(event);
			// 같은 트랜잭션에서 한 다른 일도 커밋돼야 한다.
			auditLog.record(AuditEntry.of(sameTransaction));
		});

		assertThat(find(event)).hasSize(1);
		assertThat(auditLogService.search(null, sameTransaction.code(), null, null, null, null, null, 0, 10))
				.hasSize(1);
	}

	@Test
	void 같은_이벤트가_동시에_여러_번_와도_한_줄이다() throws Exception {
		AuditTestEvent event = newEvent(1, null);
		ExecutorService executor = Executors.newFixedThreadPool(THREADS);
		CountDownLatch ready = new CountDownLatch(THREADS);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (int i = 0; i < THREADS; i++) {
				futures.add(executor.submit(() -> {
					ready.countDown();
					start.await();
					auditLogService.recordEvent(event);
					return null;
				}));
			}
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			for (Future<?> future : futures) {
				// 예외가 났으면 여기서 다시 던져진다.
				future.get(30, TimeUnit.SECONDS);
			}
		}
		finally {
			executor.shutdownNow();
		}

		assertThat(find(event)).hasSize(1);
	}

	// 이벤트 하나를 적는 데 DB 연결을 하나만 쓴다. 둘을 쓰면 이벤트가 연결 풀 크기만큼 몰릴 때 서로 기다리며 멈춘다.
	@Test
	void 이벤트가_연결_풀보다_많이_한꺼번에_와도_모두_적힌다() {
		String type = TestAuditAction.newCode();
		List<AuditTestEvent> events = new ArrayList<>();
		for (int i = 1; i <= 40; i++) {
			events.add(new AuditTestEvent(EventMetadata.create(type, 1, null, Actor.none(), clock), i, null));
		}

		transaction.executeWithoutResult(status -> events.forEach(publisher::publishEvent));

		await().atMost(Duration.ofSeconds(20))
				.untilAsserted(() -> assertThat(auditLogService
						.search(null, type, null, null, null, null, null, 0, 100)
						.getTotalElements()).isEqualTo(40));
	}

	// 저장 실패(DB 장애 등)를 삼키면 그 이벤트의 기록이 조용히 빠진다. 던져야 발행 기록에 남아 다시 보내진다.
	@Test
	void 저장에_실패하면_그대로_던진다() {
		AuditTestEvent event = newEvent(1, null);
		AuditRecordRepository saveFails = mock(AuditRecordRepository.class, delegatesTo(records));
		DataIntegrityViolationException failure = new DataIntegrityViolationException("저장 실패");
		doThrow(failure).when(saveFails)
				.insertEventIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any());
		AuditLogService failing = new AuditLogService(saveFails, jsonMapper, transactionManager, currentActor, clock);

		assertThatThrownBy(() -> failing.recordEvent(event)).isSameAs(failure);

		assertThat(find(event)).isEmpty();
	}

	// 컬럼보다 큰 값을 저장하려 들면 몇 번을 다시 받아도 실패한다. 잘라서 넣으면 깨진 JSON이 된다. 내용 없이 표시만 남긴다.
	@Test
	void 이벤트_내용은_정확히_컬럼_크기까지_그대로_적고_한_글자_넘으면_표시만_남긴다() {
		int overhead = "{\"passId\":1,\"note\":\"\"}".length();
		AuditTestEvent fits = newEvent(1, "a".repeat(AuditRecord.MAX_DETAIL_LENGTH - overhead));
		AuditTestEvent over = newEvent(1, "a".repeat(AuditRecord.MAX_DETAIL_LENGTH - overhead + 1));

		auditLogService.recordEvent(fits);
		auditLogService.recordEvent(over);

		assertThat(find(fits)).singleElement()
				.satisfies(saved -> assertThat(saved.getDetailJson()).hasSize(AuditRecord.MAX_DETAIL_LENGTH)
						.startsWith("{\"passId\":1,\"note\":\"aaa"));
		assertThat(find(over)).singleElement()
				.satisfies(saved -> assertThat(saved.getDetailJson()).isEqualTo(AuditLogService.TRUNCATED));
	}

	// 이벤트에 개인정보를 넣지 않는 것이 규칙이지만, 어긴 이벤트가 와도 그대로 적지 않는다.
	// 예외로 거부하면 그 이벤트가 끝없이 다시 오므로 내용 없이 적는다. 가린 번호도 뒷자리가 남으므로 같다.
	@Test
	void 전화번호처럼_보이는_값이_든_이벤트는_어디에_들었든_내용_없이_적는다() {
		AuditTestEvent top = newEvent(1012345678L, "학부모 010-1234-5678");
		AuditTestEvent masked = newEvent(1, "010-****-5678");
		NestedEvent inList = new NestedEvent(newMetadata(), List.of("메모", "010-1234-5678"), new Inner(1, "없음"));
		NestedEvent inObject = new NestedEvent(newMetadata(), List.of("메모"), new Inner(1, "01012345678"));
		NestedEvent clean = new NestedEvent(newMetadata(), List.of("메모"), new Inner(1012345678L, "없음"));

		List.of(top, masked, inList, inObject, clean).forEach(auditLogService::recordEvent);

		assertThat(List.of(top, masked, inList, inObject)).allSatisfy(event -> assertThat(find(event)).singleElement()
				.satisfies(saved -> assertThat(saved.getDetailJson()).isEqualTo(AuditLogService.REDACTED)));
		// 숫자로 실린 ID는 전화번호 모양이어도 그대로 적는다.
		assertThat(find(clean).getFirst().getDetailJson())
				.isEqualTo("{\"notes\":[\"메모\"],\"inner\":{\"id\":1012345678,\"memo\":\"없음\"}}");
	}

	// 내용을 읽지 못해도 "누가 언제 무엇을"은 적는다. 던지면 몇 번을 다시 받아도 같은 자리에서 실패한다.
	@Test
	void 내용을_읽을_수_없는_이벤트도_봉투만으로_한_줄_적힌다() {
		BrokenEvent event = new BrokenEvent(newMetadata());

		auditLogService.recordEvent(event);

		assertThat(find(event)).singleElement().satisfies(saved -> {
			assertThat(saved.getDetailJson()).isEqualTo(AuditLogService.UNREADABLE);
			assertThat(saved.getActorId()).isEqualTo(7L);
		});
	}

	@Test
	void 봉투만_있는_이벤트는_상세가_없고_값이_없는_필드는_null로_적힌다() {
		EnvelopeOnlyEvent envelopeOnly = new EnvelopeOnlyEvent(
				EventMetadata.create(TestAuditAction.newCode(), 1, null, Actor.none(), clock));
		AuditTestEvent withNull = newEvent(3, null);

		auditLogService.recordEvent(envelopeOnly);
		auditLogService.recordEvent(withNull);

		assertThat(find(envelopeOnly)).singleElement().satisfies(saved -> {
			assertThat(saved.getDetailJson()).isNull();
			assertThat(saved.getActorId()).isNull();
			assertThat(saved.getCampusId()).isNull();
		});
		assertThat(find(withNull).getFirst().getDetailJson()).isEqualTo("{\"passId\":3,\"note\":null}");
	}

	private AuditTestEvent newEvent(long passId, String note) {
		return new AuditTestEvent(newMetadata(), passId, note);
	}

	private EventMetadata newMetadata() {
		return EventMetadata.create(TestAuditAction.newCode(), 1, 2L, Actor.user(7), clock);
	}

	private List<AuditRecord> find(DomainEvent event) {
		return auditLogService.search(null, event.metadata().eventType(), null, null, null, null, null, 0, 100)
				.getContent();
	}

	private int incompleteAuditPublications(AuditTestEvent event) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM event_publication WHERE completion_date IS NULL "
				+ "AND listener_id LIKE '%AuditEventListener%' AND serialized_event LIKE ?", Integer.class,
				"%" + event.metadata().eventId() + "%");
	}

	record SubjectEvent(EventMetadata metadata, long passId) implements DomainEvent {

		@Override
		public EntityRef subject() {
			return EntityRef.of("pass", passId);
		}

	}

	record EnvelopeOnlyEvent(EventMetadata metadata) implements DomainEvent {
	}

	record NestedEvent(EventMetadata metadata, List<String> notes, Inner inner) implements DomainEvent {
	}

	record Inner(long id, String memo) {
	}

	// JSON으로 바꾸는 중에 예외가 나는 이벤트.
	record BrokenEvent(EventMetadata metadata) implements DomainEvent {

		public String getBroken() {
			throw new IllegalStateException("읽을 수 없는 필드");
		}

	}

}
