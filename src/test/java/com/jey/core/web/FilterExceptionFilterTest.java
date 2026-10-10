package com.jey.core.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// 실제 서버에서의 응답은 FilterStageErrorTest가 본다. 여기서는 그 테스트로 만들기 어려운 갈래를 확인한다.
@ExtendWith(OutputCaptureExtension.class)
class FilterExceptionFilterTest {

	private final HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);

	private final FilterExceptionFilter filter = new FilterExceptionFilter(resolver);

	private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/something");

	private final MockHttpServletResponse response = new MockHttpServletResponse();

	@Test
	void 오류가_없으면_아무것도_하지_않는다() throws Exception {
		filter.doFilter(request, response, (req, res) -> ((HttpServletResponse) res).setStatus(204));

		assertThat(response.getStatus()).isEqualTo(204);
		verifyNoInteractions(resolver);
	}

	// 삼키면 잘린 응답이 정상으로 끝난 것처럼 보인다. 다시 던져서 컨테이너가 연결을 끊게 한다.
	@Test
	void 응답이_이미_나가기_시작했으면_다시_던진다(CapturedOutput output) {
		var failure = new IllegalStateException("after commit");
		FilterChain chain = (req, res) -> {
			res.getWriter().write("partial");
			res.flushBuffer();
			throw failure;
		};

		assertThatThrownBy(() -> filter.doFilter(request, response, chain)).isSameAs(failure);

		verifyNoInteractions(resolver);
		assertThat(output).contains("응답을 보내는 중에 오류가 났다: GET /api/v1/something");
	}

	@Test
	void 응답이_이미_나가기_시작한_뒤의_JVM_오류도_그대로_다시_던진다() {
		var failure = new NoClassDefFoundError("missing");
		FilterChain chain = (req, res) -> {
			res.flushBuffer();
			throw failure;
		};

		assertThatThrownBy(() -> filter.doFilter(request, response, chain)).isSameAs(failure);
	}

	@Test
	void 필터의_예외를_예외_처리기로_넘긴다() throws Exception {
		var failure = new IllegalStateException("redis down");
		when(resolver.resolveException(request, response, null, failure)).thenReturn(new ModelAndView());

		filter.doFilter(request, response, (req, res) -> {
			throw failure;
		});
	}

	// 예외 처리기는 Exception만 받는다. JVM 오류는 원인으로 감싸서 넘긴다.
	@Test
	void JVM_오류는_원인으로_감싸서_넘긴다() throws Exception {
		var failure = new NoClassDefFoundError("missing");
		when(resolver.resolveException(any(), any(), any(), any())).thenAnswer(invocation -> {
			Exception handled = invocation.getArgument(3);
			assertThat(handled).isInstanceOf(IllegalStateException.class).hasCause(failure);
			return new ModelAndView();
		});

		filter.doFilter(request, response, (req, res) -> {
			throw failure;
		});
	}

	// 버려진 응답의 Content-Length가 남으면 오류 본문이 잘리고, Retry-After가 남으면 500에 엉뚱한 안내가 붙는다.
	// CORS 헤더는 남겨야 다른 출처의 프론트가 오류 응답을 읽는다.
	@Test
	void 쓰다_만_본문과_헤더는_버리고_CORS_헤더만_남긴다() throws Exception {
		when(resolver.resolveException(any(), any(), any(), any())).thenAnswer(invocation -> {
			HttpServletResponse res = invocation.getArgument(1);
			res.setStatus(500);
			res.getWriter().write("{\"code\":\"COMMON_INTERNAL_ERROR\"}");
			return new ModelAndView();
		});
		FilterChain chain = (req, res) -> {
			HttpServletResponse httpResponse = (HttpServletResponse) res;
			httpResponse.setHeader("Access-Control-Allow-Origin", "http://localhost:5173");
			httpResponse.setHeader("Access-Control-Allow-Credentials", "true");
			httpResponse.addHeader("Vary", "Origin");
			httpResponse.setHeader("Retry-After", "120");
			httpResponse.setHeader("Content-Length", "9999");
			httpResponse.getWriter().write("half-written body");
			throw new IOException("boom");
		};

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(500);
		assertThat(response.getContentAsString()).isEqualTo("{\"code\":\"COMMON_INTERNAL_ERROR\"}");
		assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:5173");
		assertThat(response.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
		assertThat(response.getHeaders("Vary")).containsExactly("Origin");
		assertThat(response.getHeader("Retry-After")).isNull();
		assertThat(response.getHeader("Content-Length")).isNull();
	}

	// 예외 처리기 안에서 응답을 쓰다 실패한 경우다.
	@Test
	void 예외_처리기가_응답을_쓰지_못하면_500으로_끝내고_원인을_남긴다(CapturedOutput output) throws Exception {
		when(resolver.resolveException(any(), any(), any(), any())).thenReturn(null);

		filter.doFilter(request, response, (req, res) -> {
			throw new ServletException("root-cause-marker");
		});

		assertThat(response.getStatus()).isEqualTo(500);
		assertThat(output).contains("Problem Details로 변환하지 못했다").contains("root-cause-marker");
	}

	// 처리하다 난 오류만 남으면 원래 원인(세션 저장소 장애 등)은 어디에도 남지 않는다.
	@Test
	void 처리하다_다시_오류가_나면_원래_원인을_로그에_남기고_그_오류를_던진다(CapturedOutput output) {
		var secondary = new IllegalArgumentException("resolver failed");
		when(resolver.resolveException(any(), any(), any(), any())).thenThrow(secondary);

		assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
			throw new IllegalStateException("original-cause-marker");
		})).isSameAs(secondary);

		assertThat(output).contains("처리하다 다시 오류가 났다").contains("original-cause-marker");
	}

	@Test
	void 모든_필터보다_앞에서_돈다() {
		assertThat(filter.getOrder()).isEqualTo(Integer.MIN_VALUE);
	}

}
