package com.jey.core.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * 필터 단계에서 난 오류를 컨트롤러에서 난 오류와 같은 형식(Problem Details)으로 응답한다.
 *
 * <p>필터에서 난 오류는 컨트롤러의 예외 처리기를 거치지 않는다. 그대로 두면 서블릿 컨테이너가 오류 경로({@code /error})로 다시 보내는데,
 * 그 요청이 인증 검사에 걸려 401이 된다. 세션 저장소 장애가 "로그인이 풀렸다"로 보이게 된다.
 * 그래서 모든 필터의 바깥에서 잡아 MVC 예외 처리기로 넘긴다.
 */
@Component
class FilterExceptionFilter extends OncePerRequestFilter implements Ordered {

	private static final Logger log = LoggerFactory.getLogger(FilterExceptionFilter.class);

	private final HandlerExceptionResolver exceptionResolver;

	// HandlerExceptionResolver 빈이 여러 개라 MVC의 합성 리졸버를 이름으로 지정한다.
	FilterExceptionFilter(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
		this.exceptionResolver = exceptionResolver;
	}

	// 세션 필터와 보안 필터를 포함한 모든 필터보다 앞이어야 그 필터들의 오류를 잡을 수 있다.
	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		try {
			chain.doFilter(request, response);
		}
		catch (Exception ex) {
			respond(request, response, ex);
		}
		catch (Error error) {
			// 예외 처리기는 Exception만 받는다. 메모리 부족이나 클래스 누락 같은 JVM 오류도 같은 형식으로 응답하려고 감싼다.
			respond(request, response, new IllegalStateException("필터 단계에서 JVM 오류가 났다", error));
		}
	}

	private void respond(HttpServletRequest request, HttpServletResponse response, Exception ex) throws IOException {
		// 응답이 이미 나가기 시작했으면 형식을 바꿀 수 없다. 흔적만 남긴다.
		if (response.isCommitted()) {
			log.error("응답을 보내는 중에 오류가 났다: {} {}", request.getMethod(), shorten(request.getRequestURI()), ex);
			return;
		}
		// 쓰다 만 본문만 비운다. 헤더는 남긴다. CORS 헤더까지 지우면 프론트가 오류 응답을 읽지 못한다.
		response.resetBuffer();
		if (this.exceptionResolver.resolveException(request, response, null, ex) == null) {
			log.error("필터 단계 오류를 Problem Details로 변환하지 못했다: {} {}", request.getMethod(),
					shorten(request.getRequestURI()), ex);
			response.sendError(HttpStatus.INTERNAL_SERVER_ERROR.value());
		}
	}

	private static String shorten(String uri) {
		return (uri != null && uri.length() > 200) ? uri.substring(0, 200) + "…" : uri;
	}

}
