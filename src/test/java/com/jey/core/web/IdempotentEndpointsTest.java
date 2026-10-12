package com.jey.core.web;

import java.util.List;
import java.util.stream.Stream;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.UnsatisfiedServletRequestParameterException;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 컨트롤러 조회가 실패했을 때 "키가 필요 없다"로 답하면 필터가 조용히 꺼진다.
// 뒤에서 같은 이유로 거절될 요청만 넘기고, 까닭을 모르는 실패는 요청을 멈춘다.
class IdempotentEndpointsTest {

	private Object handler;

	private Exception failure;

	private final RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping() {
		@Override
		protected HandlerMethod getHandlerInternal(HttpServletRequest request) throws Exception {
			if (failure != null) {
				throw failure;
			}
			return (HandlerMethod) handler;
		}
	};

	private final IdempotentEndpoints endpoints = new IdempotentEndpoints(new ObjectProvider<>() {
		@Override
		public RequestMappingHandlerMapping getObject() {
			return mapping;
		}

		@Override
		public Stream<RequestMappingHandlerMapping> stream() {
			return Stream.of(mapping);
		}
	});

	@Test
	void 표시가_붙은_메서드만_키가_필요하다() throws Exception {
		IdempotencyTestController controller = new IdempotencyTestController();
		handler = new HandlerMethod(controller, IdempotencyTestController.class.getDeclaredMethod("empty"));
		assertThat(endpoints.requiresKey(post())).isTrue();

		handler = new HandlerMethod(controller, IdempotencyTestController.class.getDeclaredMethod("unguarded"));
		assertThat(endpoints.requiresKey(post())).isFalse();
	}

	// 없는 경로다. 뒤에서 404가 된다.
	@Test
	void 맞는_컨트롤러가_없으면_키가_필요_없다() {
		handler = null;

		assertThat(endpoints.requiresKey(post())).isFalse();
	}

	// 경로는 있지만 맞는 메서드가 없다. 어느 메서드도 실행되지 않고 뒤에서 405·415·406·400이 된다.
	@Test
	void 뒤에서_같은_이유로_거절될_요청은_키가_필요_없다() {
		List<Exception> rejections = List.of(new HttpRequestMethodNotSupportedException("POST"),
				new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON)),
				new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON)),
				new UnsatisfiedServletRequestParameterException(new String[] { "mode=x" }, java.util.Map.of()));

		for (Exception rejection : rejections) {
			failure = rejection;
			assertThat(endpoints.requiresKey(post())).as(rejection.getClass().getSimpleName()).isFalse();
		}
	}

	// 처음 구현은 여기서 false를 돌려줬고, 조회가 매번 실패하는 동안 필터가 아무것도 막지 않았다.
	@Test
	void 까닭을_모르는_실패는_키가_필요_없다고_답하지_않는다() {
		List<Exception> failures = List.of(new IllegalStateException("매핑이 준비되지 않았다"),
				new NullPointerException(), new ServletRequestBindingException("알 수 없는 바인딩 오류"),
				new jakarta.servlet.ServletException("알 수 없는 서블릿 오류"));

		for (Exception unknown : failures) {
			failure = unknown;
			assertThatThrownBy(() -> endpoints.requiresKey(post())).as(unknown.getClass().getSimpleName())
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("확인하지 못했다")
					.hasCause(unknown);
		}
	}

	private static MockHttpServletRequest post() {
		return new MockHttpServletRequest("POST", "/api/v1/things");
	}

}
