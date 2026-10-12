package com.jey.core.config;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.jey.core.config.LogContextPropagation.LogContextAccessor;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshotFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 실제 이벤트 리스너로 넘어가는지는 LogContextPropagationTest가 본다.
// 여기서는 Spring Modulith가 실행기에 거는 것과 같은 장치에 등록했을 때의 갈래를 확인한다.
class LogContextAccessorTest {

	private final TaskDecorator decorator = new ContextPropagatingTaskDecorator(ContextSnapshotFactory.builder()
			.contextRegistry(new ContextRegistry().registerThreadLocalAccessor(new LogContextAccessor()))
			.build());

	// 실행 스레드 하나를 계속 다시 쓴다.
	private final ExecutorService worker = Executors.newSingleThreadExecutor();

	@AfterEach
	void tearDown() {
		worker.shutdownNow();
		MDC.clear();
	}

	@Test
	void 넘기는_스레드의_로그_문맥이_실행_스레드에_보인다() throws Exception {
		MDC.put("requestId", "submitted-request");
		MDC.put("accountId", "7");
		AtomicReference<Map<String, String>> seen = new AtomicReference<>();

		run(decorator.decorate(() -> seen.set(MDC.getCopyOfContextMap())));

		assertThat(seen.get()).containsOnly(Map.entry("requestId", "submitted-request"), Map.entry("accountId", "7"));
	}

	// 넘긴 뒤에 넘긴 쪽이 문맥을 바꿔도(요청이 끝나 비워도) 작업은 넘길 때의 값을 본다.
	@Test
	void 넘길_때의_값을_넘긴다() throws Exception {
		MDC.put("requestId", "submitted-request");
		AtomicReference<String> seen = new AtomicReference<>();
		Runnable decorated = decorator.decorate(() -> seen.set(MDC.get("requestId")));
		MDC.clear();

		run(decorated);

		assertThat(seen).hasValue("submitted-request");
	}

	@Test
	void 작업이_끝나면_실행_스레드에_값이_남지_않는다() throws Exception {
		MDC.put("requestId", "submitted-request");
		run(decorator.decorate(() -> {
		}));

		assertThat(logContextOnWorker()).isNullOrEmpty();
	}

	@Test
	void 작업이_예외로_끝나도_실행_스레드에_값이_남지_않는다() throws Exception {
		MDC.put("requestId", "submitted-request");
		Runnable failing = decorator.decorate(() -> {
			throw new IllegalStateException("boom");
		});
		assertThatThrownBy(() -> run(failing)).hasRootCauseMessage("boom");

		assertThat(logContextOnWorker()).isNullOrEmpty();
	}

	// 로그 문맥 없이 넘긴 작업(주기 작업 등)은 실행 스레드에 있던 값을 보지 않는다.
	@Test
	void 로그_문맥_없이_넘긴_작업은_빈_문맥에서_돈다() throws Exception {
		run(() -> MDC.put("requestId", "left-on-worker"));
		AtomicReference<String> seen = new AtomicReference<>("unset");

		run(decorator.decorate(() -> seen.set(MDC.get("requestId"))));

		assertThat(seen).hasValue(null);
	}

	// 같은 스레드에서 바로 실행하는 실행기도 있다. 그때는 실행한 뒤에 원래 있던 문맥이 그대로 남아야 한다.
	@Test
	void 같은_스레드에서_실행하면_원래_문맥으로_돌아온다() {
		MDC.put("requestId", "at-submit");
		AtomicReference<String> seen = new AtomicReference<>();
		Runnable decorated = decorator.decorate(() -> seen.set(MDC.get("requestId")));
		MDC.put("requestId", "at-run");

		decorated.run();

		assertThat(seen).hasValue("at-submit");
		assertThat(MDC.get("requestId")).isEqualTo("at-run");
	}

	private Map<String, String> logContextOnWorker() throws Exception {
		AtomicReference<Map<String, String>> context = new AtomicReference<>();
		run(() -> context.set(MDC.getCopyOfContextMap()));
		return context.get();
	}

	private void run(Runnable task) throws Exception {
		worker.submit(task).get(5, TimeUnit.SECONDS);
	}

}
