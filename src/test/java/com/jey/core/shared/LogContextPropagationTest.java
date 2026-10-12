package com.jey.core.shared;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.jey.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// 이벤트 리스너는 요청을 처리한 스레드가 아닌 다른 스레드에서 돈다. 로그 문맥(MDC)은 스레드마다 따로라서,
// 넘겨 주지 않으면 리스너가 남긴 로그를 그 일을 일으킨 요청과 이어 볼 수 없다 (ADR-0028).
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class LogContextPropagationTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	@Autowired
	ApplicationEventPublisher publisher;

	@Autowired
	TransactionTemplate transaction;

	@Autowired
	Clock clock;

	@Autowired
	TestDomainEventListener listener;

	@AfterEach
	void clearLogContext() {
		MDC.clear();
	}

	@Test
	void 요청이_낸_이벤트의_리스너에_요청의_로그_문맥이_넘어간다() {
		MDC.put("requestId", "request-that-published-1");
		MDC.put("accountId", "7");
		TestDomainEvent event = newEvent(51);

		publish(event);

		awaitReceived(event);
		assertThat(listener.logContextOf(event.metadata().eventId())).containsEntry("requestId", "request-that-published-1")
				.containsEntry("accountId", "7");
		assertThat(listener.threadOf(event.metadata().eventId())).isNotEqualTo(Thread.currentThread().getName());
	}

	// 실행 스레드는 다시 쓰인다. 앞의 일이 남긴 값이 요청 없이 일어난 일(주기 작업 등)의 로그에 찍히면 안 된다.
	// 여기서는 결과만 본다: 로그 문맥을 받았던 바로 그 스레드에서, 로그 문맥 없이 낸 이벤트의 리스너가 빈 문맥으로 돈다.
	// 빈 값을 넘기는 방식(null이 아닌 빈 Map)이 깨졌을 때 드러나는 갈래는 LogContextAccessorTest가 본다.
	@Test
	void 로그_문맥을_받았던_스레드에서도_로그_문맥_없이_낸_이벤트의_리스너는_빈_문맥에서_돈다() {
		// 실행 스레드를 모두 여러 번 쓰게 한다(기본 스레드 수 8, 이벤트마다 리스너가 둘).
		List<TestDomainEvent> earlier = new ArrayList<>();
		for (int i = 0; i < 48; i++) {
			MDC.put("requestId", "earlier-request-" + i);
			TestDomainEvent event = newEvent(600 + i);
			earlier.add(event);
			publish(event);
		}
		Set<String> threadsThatHadContext = new HashSet<>();
		for (int i = 0; i < earlier.size(); i++) {
			TestDomainEvent event = earlier.get(i);
			awaitReceived(event);
			assertThat(listener.logContextOf(event.metadata().eventId())).containsEntry("requestId",
					"earlier-request-" + i);
			threadsThatHadContext.add(listener.threadOf(event.metadata().eventId()));
		}
		MDC.clear();
		List<TestDomainEvent> withoutContext = new ArrayList<>();
		for (int i = 0; i < 16; i++) {
			TestDomainEvent event = newEvent(700 + i);
			withoutContext.add(event);
			publish(event);
		}

		Set<String> threadsUsed = new HashSet<>();
		for (TestDomainEvent event : withoutContext) {
			awaitReceived(event);
			assertThat(listener.logContextOf(event.metadata().eventId())).isEmpty();
			threadsUsed.add(listener.threadOf(event.metadata().eventId()));
		}
		// 스레드를 실제로 다시 썼는지 본다. 다시 쓰지 않았다면 위의 확인은 새 스레드가 깨끗하다는 것만 보여 준다.
		assertThat(threadsUsed).containsAnyElementsOf(threadsThatHadContext);
	}

	private void awaitReceived(TestDomainEvent event) {
		await().atMost(TIMEOUT).untilAsserted(() -> assertThat(listener.received()).contains(event));
	}

	private TestDomainEvent newEvent(long targetId) {
		return new TestDomainEvent(EventMetadata.create("core.test-happened", 1, 1L, Actor.user(7), clock), targetId);
	}

	// 리스너는 트랜잭션이 커밋된 뒤에 실행되므로 트랜잭션 안에서 발행한다.
	private void publish(TestDomainEvent event) {
		transaction.executeWithoutResult(status -> publisher.publishEvent(event));
	}

}
