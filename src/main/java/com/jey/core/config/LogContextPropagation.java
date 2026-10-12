package com.jey.core.config;

import java.util.Map;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;

/**
 * 다른 스레드로 넘기는 작업에 넘기는 쪽의 로그 문맥(MDC — 요청 ID, 계정 ID)을 같이 넘긴다 (ADR-0028).
 * 이벤트 리스너는 요청을 처리한 스레드가 아닌 곳에서 돌기 때문에, 넘기지 않으면 리스너의 로그를 그 요청과 이어 볼 수 없다.
 *
 * <p>넘기는 일 자체는 Spring Modulith가 이벤트 실행기에 걸어 둔 문맥 전달 장치({@code ContextPropagatingTaskDecorator})가 한다.
 * 그 장치는 등록된 값만 넘기므로, 여기서는 로그 문맥을 넘길 값으로 등록한다.
 * {@code TaskDecorator} 빈을 따로 두지 않는 이유: Spring Modulith가 실행기의 장식을 자기 것으로 바꿔 끼워서 그 빈은 불리지 않는다.
 */
@Configuration(proxyBeanMethods = false)
class LogContextPropagation {

	LogContextPropagation() {
		// 같은 이름으로 다시 등록하면 앞의 것을 바꾼다. 컨텍스트가 여러 번 떠도(테스트) 하나만 남는다.
		ContextRegistry.getInstance().registerThreadLocalAccessor(new LogContextAccessor());
	}

	static final class LogContextAccessor implements ThreadLocalAccessor<Map<String, String>> {

		static final String KEY = "jey.log-context";

		@Override
		public Object key() {
			return KEY;
		}

		// 비어 있어도 null이 아니라 빈 값을 돌려준다. null이면 "넘길 것이 없다"로 읽혀 실행 스레드에 있던 값이 그대로 남는다.
		// 빈 값을 넘겨야 로그 문맥 없이 넘긴 작업(주기 작업 등)이 빈 문맥에서 돈다.
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
