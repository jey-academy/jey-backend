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
import com.jey.core.audit.domain.AuditRecord;
import com.jey.core.audit.domain.AuditRecordRepository;
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

import static com.jey.core.audit.AuditLogTest.newAction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
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
	Clock clock;

	// 감사 로그는 이 이벤트 타입을 모른다. 공통 봉투 타입으로 받아서 봉투의 값과 내용을 적는다.
	@Test
	void 발행된_이벤트가_한_줄로_기록된다() {
		AuditTestEvent event = newEvent(42, "메모");

		transaction.executeWithoutResult(status -> publisher.publishEvent(event));

		await().atMost(TIMEOUT).untilAsserted(() -> assertThat(find(event)).hasSize(1));
		AuditRecord saved = find(event).getFirst();
		assertThat(saved.getAction()).isEqualTo(event.metadata().eventType());
		assertThat(saved.getActorId()).isEqualTo(7L);
		assertThat(saved.getCampusId()).isEqualTo(2L);
		// 적힌 시각이 아니라 일어난 시각이다.
		assertThat(saved.getOccurredAt()).isEqualTo(event.metadata().occurredAt().truncatedTo(ChronoUnit.MICROS));
		assertThat(saved.getEventId()).isEqualTo(event.metadata().eventId().toString());
		// 봉투는 컬럼으로 옮겼으므로 상세에는 내용만 있다.
		assertThat(saved.getDetailJson()).isEqualTo("{\"passId\":42,\"note\":\"메모\"}");
		assertThat(saved.getTargetType()).isNull();
		// 리스너는 요청 밖에서 돈다. 저장한 요청의 계정은 없다.
		assertThat(saved.getCreatedBy()).isNull();
		await().atMost(TIMEOUT).untilAsserted(() -> assertThat(incompleteAuditPublications(event)).isZero());
	}

	// 리스너는 실패하면 다시 실행된다. 저장한 뒤 완료 표시 전에 죽으면 같은 이벤트가 또 온다.
	@Test
	void 같은_이벤트가_다시_와도_한_줄이고_실패하지_않는다() {
		AuditTestEvent event = newEvent(1, null);

		auditLogService.recordEvent(event);
		auditLogService.recordEvent(event);

		assertThat(find(event)).hasSize(1);
	}

	// "이미 적혔는지 확인"과 저장 사이에 다른 쪽이 먼저 적으면 유니크 키가 막는다.
	// 그것을 실패로 돌려주면 그 이벤트는 끝없이 다시 온다.
	@Test
	void 확인과_저장_사이에_먼저_적힌_이벤트는_성공으로_끝난다() {
		AuditTestEvent event = newEvent(1, null);
		auditLogService.recordEvent(event);
		AuditRecordRepository lookupMisses = mock(AuditRecordRepository.class, delegatesTo(records));
		doReturn(false).when(lookupMisses).existsByEventId(any());
		AuditLogService racing = new AuditLogService(lookupMisses, jsonMapper, transactionManager, clock);

		racing.recordEvent(event);

		assertThat(find(event)).hasSize(1);
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

	// 유니크 키가 아닌 저장 실패(DB 장애 등)를 삼키면 그 이벤트의 기록이 조용히 빠진다. 다시 시도되게 던진다.
	@Test
	void 유니크_키가_아닌_저장_실패는_그대로_던진다() {
		AuditTestEvent event = newEvent(1, null);
		AuditRecordRepository saveFails = mock(AuditRecordRepository.class, delegatesTo(records));
		DataIntegrityViolationException failure = new DataIntegrityViolationException("다른 제약 위반");
		doThrow(failure).when(saveFails).saveAndFlush(any());
		AuditLogService failing = new AuditLogService(saveFails, jsonMapper, transactionManager, clock);

		assertThatThrownBy(() -> failing.recordEvent(event)).isSameAs(failure);

		assertThat(find(event)).isEmpty();
	}

	// 잘라서 넣으면 깨진 JSON이 된다. 내용 없이 적고 표시를 남긴다.
	@Test
	void 내용이_큰_이벤트는_내용_없이_표시만_남긴다() {
		AuditTestEvent event = newEvent(1, "가".repeat(2_500));

		auditLogService.recordEvent(event);

		assertThat(find(event)).singleElement()
				.satisfies(saved -> assertThat(saved.getDetailJson()).isEqualTo("{\"truncated\":true}"));
	}

	// 이벤트에 개인정보를 넣지 않는 것이 규칙이지만, 어긴 이벤트가 와도 그대로 적지 않는다.
	// 예외로 거부하면 그 이벤트가 끝없이 다시 오므로 내용 없이 적는다.
	@Test
	void 전화번호처럼_보이는_값이_든_이벤트는_내용_없이_적는다() {
		AuditTestEvent event = newEvent(1012345678L, "학부모 010-1234-5678");
		AuditTestEvent numeric = newEvent(1012345678L, "메모");

		auditLogService.recordEvent(event);
		auditLogService.recordEvent(numeric);

		assertThat(find(event)).singleElement()
				.satisfies(saved -> assertThat(saved.getDetailJson()).isEqualTo("{\"redacted\":true}"));
		// 숫자로 실린 ID는 전화번호 모양이어도 그대로 적는다.
		assertThat(find(numeric)).singleElement()
				.satisfies(saved -> assertThat(saved.getDetailJson()).contains("1012345678"));
	}

	@Test
	void 행위자와_지점이_없는_이벤트도_적힌다() {
		AuditTestEvent event = new AuditTestEvent(EventMetadata.create(newAction(), 1, null, null, clock), 5, null);

		auditLogService.recordEvent(event);

		assertThat(find(event)).singleElement().satisfies(saved -> {
			assertThat(saved.getActorId()).isNull();
			assertThat(saved.getCampusId()).isNull();
		});
	}

	private AuditTestEvent newEvent(long passId, String note) {
		return new AuditTestEvent(EventMetadata.create(newAction(), 1, 2L, 7L, clock), passId, note);
	}

	private List<AuditRecord> find(AuditTestEvent event) {
		return auditLogService.search(null, event.metadata().eventType(), null, null, null, null, 0, 100)
				.getContent();
	}

	private int incompleteAuditPublications(AuditTestEvent event) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM event_publication WHERE completion_date IS NULL "
				+ "AND listener_id LIKE '%AuditEventListener%' AND serialized_event LIKE ?", Integer.class,
				"%" + event.metadata().eventId() + "%");
	}

}
