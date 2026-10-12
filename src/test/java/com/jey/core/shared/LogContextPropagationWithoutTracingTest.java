package com.jey.core.shared;

import java.time.Clock;
import java.time.Duration;

import com.jey.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.task.ThreadPoolTaskExecutorCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Spring Modulith의 관측 구성은 이벤트 실행기에 문맥 전달 장식을 걸어 준다. 그 구성은 설정 하나로 꺼진다.
// 로그 문맥의 전달이 그 구성에만 기대면, 운영 설정에서 추적을 끄는 순간 리스너의 로그에서 요청 ID가 조용히 사라진다.
// 다른 테스트는 모두 그 구성이 켜진 채로 돌기 때문에 그것을 알아채지 못한다 (ADR-0028).
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "management.tracing.enabled=false")
@ActiveProfiles("test")
class LogContextPropagationWithoutTracingTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	@Autowired
	ApplicationContext context;

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

	// 이 테스트가 보려는 상황이 맞는지 먼저 본다. 실행기를 고치는 빈이 남아 있으면 관측 구성이 꺼지지 않은 것이다.
	@Test
	void 관측_구성이_꺼져_있다() {
		assertThat(context.getBeansOfType(ThreadPoolTaskExecutorCustomizer.class)).isEmpty();
	}

	@Test
	void 관측_구성이_꺼져_있어도_리스너에_요청의_로그_문맥이_넘어간다() {
		MDC.put("requestId", "request-without-tracing");
		MDC.put("accountId", "7");
		TestDomainEvent event = new TestDomainEvent(
				EventMetadata.create("core.test-happened", 1, 1L, Actor.user(7), clock), 53);

		transaction.executeWithoutResult(status -> publisher.publishEvent(event));

		await().atMost(TIMEOUT).untilAsserted(() -> assertThat(listener.received()).contains(event));
		assertThat(listener.logContextOf(event.metadata().eventId())).containsEntry("requestId", "request-without-tracing")
				.containsEntry("accountId", "7");
		assertThat(listener.threadOf(event.metadata().eventId())).isNotEqualTo(Thread.currentThread().getName());
	}

	@Test
	void 관측_구성이_꺼져_있어도_로그_문맥_없이_낸_이벤트의_리스너는_빈_문맥에서_돈다() {
		MDC.put("requestId", "earlier-request-without-tracing");
		for (int i = 0; i < 16; i++) {
			transaction.executeWithoutResult(status -> publisher.publishEvent(new TestDomainEvent(
					EventMetadata.create("core.test-happened", 1, 1L, Actor.user(7), clock), 800)));
		}
		MDC.clear();
		TestDomainEvent event = new TestDomainEvent(
				EventMetadata.create("core.test-happened", 1, 1L, Actor.user(7), clock), 54);

		transaction.executeWithoutResult(status -> publisher.publishEvent(event));

		await().atMost(TIMEOUT).untilAsserted(() -> assertThat(listener.received()).contains(event));
		assertThat(listener.logContextOf(event.metadata().eventId())).isEmpty();
	}

}
