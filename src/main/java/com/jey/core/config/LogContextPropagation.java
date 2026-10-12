package com.jey.core.config;

import java.util.Map;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;

/**
 * 다른 스레드로 넘기는 작업에 넘기는 쪽의 로그 문맥(MDC — 요청 ID, 계정 ID)을 같이 넘긴다 (ADR-0028).
 * 이벤트 리스너는 요청을 처리한 스레드가 아닌 곳에서 돌기 때문에, 넘기지 않으면 리스너의 로그를 그 요청과 이어 볼 수 없다.
 *
 * <p>넘기는 장치는 문맥 전달 장식({@link ContextPropagatingTaskDecorator})이고, 그 장치는 등록된 값만 넘긴다.
 * 여기서 하는 일은 둘이다: 로그 문맥을 넘길 값으로 등록하고, 그 장식을 실행기에 건다.
 *
 * <p>장식을 거는 길은 둘이고 어느 쪽이든 같은 장식이 걸린다.
 * Spring Modulith의 관측 구성이 켜져 있으면(기본) 그쪽이 이벤트 실행기의 장식을 자기 것으로 바꿔 끼운다. 같은 종류의 장식이다.
 * 그 구성이 꺼져 있으면({@code management.tracing.enabled=false}) Spring Boot가 아래의 빈을 실행기에 건다.
 * 빈을 두지 않으면 그 설정 하나로 전달이 조용히 꺼진다.
 */
@Configuration(proxyBeanMethods = false)
class LogContextPropagation {

	LogContextPropagation() {
		// 같은 이름으로 다시 등록하면 앞의 것을 바꾼다. 컨텍스트가 여러 번 떠도(테스트) 하나만 남는다.
		ContextRegistry.getInstance().registerThreadLocalAccessor(new LogContextAccessor());
	}

	@Bean
	TaskDecorator logContextTaskDecorator() {
		return new ContextPropagatingTaskDecorator();
	}

	/**
	 * 로그 문맥을 통째로 넘기고 통째로 되돌린다.
	 *
	 * <p>라이브러리에 같은 일을 하는 {@code Slf4jThreadLocalAccessor}가 있지만 쓰지 않는다.
	 * 그것은 로그 문맥이 비어 있으면 null을 돌려주는데, null은 "넘길 것이 없다"로 읽혀 실행 스레드에 있던 값이 그대로 남는다.
	 */
	static final class LogContextAccessor implements ThreadLocalAccessor<Map<String, String>> {

		private static final String KEY = "jey.log-context";

		@Override
		public Object key() {
			return KEY;
		}

		// 비어 있어도 null이 아니라 빈 값을 돌려준다. 빈 값을 넘겨야 로그 문맥 없이 넘긴 작업(주기 작업 등)이 빈 문맥에서 돈다.
		@Override
		public Map<String, String> getValue() {
			Map<String, String> context = MDC.getCopyOfContextMap();
			return (context != null) ? context : Map.of();
		}

		@Override
		public void setValue(Map<String, String> context) {
			MDC.setContextMap(context);
		}

		@Override
		public void setValue() {
			MDC.clear();
		}

	}

}
