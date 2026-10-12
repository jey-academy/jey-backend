package com.jey.core.shared;

import java.time.Clock;
import java.time.Duration;

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

		await().atMost(TIMEOUT).untilAsserted(() -> assertThat(listener.received()).contains(event));
		assertThat(listener.logContextOf(event.metadata().eventId())).containsEntry("requestId", "request-that-published-1")
				.containsEntry("accountId", "7");
	}

	// 실행 스레드는 다시 쓰인다. 앞의 일이 남긴 값이 요청 없이 일어난 일(주기 작업 등)의 로그에 찍히면 안 된다.
	@Test
	void 로그_문맥_없이_낸_이벤트의_리스너에는_앞선_일의_값이_남아_있지_않다() {
		// 실행 스레드를 모두 한 번씩 쓰게 한다(기본 스레드 수 8).
		for (int i = 0; i < 16; i++) {
			MDC.put("requestId", "earlier-request-" + i);
			publish(newEvent(600 + i));
		}
		MDC.clear();
		TestDomainEvent event = newEvent(52);

		publish(event);

		await().atMost(TIMEOUT).untilAsserted(() -> assertThat(listener.received()).contains(event));
		assertThat(listener.logContextOf(event.metadata().eventId())).isEmpty();
	}

	private TestDomainEvent newEvent(long targetId) {
		return new TestDomainEvent(EventMetadata.create("core.test-happened", 1, 1L, Actor.user(7), clock), targetId);
	}

	// 리스너는 트랜잭션이 커밋된 뒤에 실행되므로 트랜잭션 안에서 발행한다.
	private void publish(TestDomainEvent event) {
		transaction.executeWithoutResult(status -> publisher.publishEvent(event));
	}

}
