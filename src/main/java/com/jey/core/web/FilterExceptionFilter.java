package com.jey.core.web;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * 필터 단계에서 난 오류를 컨트롤러에서 난 오류와 같은 형식(Problem Details)으로 응답한다.
 *
 * <p>필터에서 난 오류는 컨트롤러의 예외 처리기를 거치지 않는다. 그대로 두면 서블릿 컨테이너가 오류 경로({@code /error})로 다시 보내는데,
 * 그 요청이 인증 검사에 걸려 401이 된다. 세션 저장소 장애가 "로그인이 풀렸다"로 보이게 된다.
 * 그래서 모든 필터의 바깥에서 잡아 MVC 예외 처리기로 넘긴다.
 *
 * <p>응답이 이미 나가기 시작한 뒤의 오류는 바꿀 수 없으므로 그대로 다시 던진다.
 * 비동기 처리와 오류 경로로 다시 들어온 요청에는 이 필터가 돌지 않는다({@link OncePerRequestFilter}의 기본 동작).
 */
@Component
class FilterExceptionFilter extends OncePerRequestFilter implements Ordered {

	private static final Logger log = LoggerFactory.getLogger(FilterExceptionFilter.class);

	private static final String CORS_HEADER_PREFIX = "access-control-";

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
			if (!respond(request, response, ex)) {
				throw ex;
			}
		}
		catch (Error error) {
			// 예외 처리기는 Exception만 받는다. 메모리 부족이나 클래스 누락 같은 JVM 오류도 같은 형식으로 응답하려고 감싼다.
			if (!respond(request, response, new IllegalStateException("필터 단계에서 JVM 오류가 났다", error))) {
				throw error;
			}
		}
	}

	/**
	 * @return 응답을 썼으면 {@code true}. 응답이 이미 나가기 시작해 쓰지 못했으면 {@code false}
	 */
	private boolean respond(HttpServletRequest request, HttpServletResponse response, Exception ex)
			throws IOException {
		// 이미 나가기 시작한 응답은 형식을 바꿀 수 없다. 호출한 쪽이 다시 던져서 컨테이너가 연결을 끊게 한다.
		// 여기서 삼키면 잘린 응답이 정상으로 끝난 것처럼 보인다.
		if (response.isCommitted()) {
			log.error("응답을 보내는 중에 오류가 났다: {} {}", request.getMethod(), shorten(request.getRequestURI()), ex);
			return false;
		}
		discardAbandonedResponse(response);
		ModelAndView resolved;
		try {
			resolved = this.exceptionResolver.resolveException(request, response, null, ex);
		}
		catch (RuntimeException | Error failure) {
			// 그대로 두면 처리하다 난 오류만 남고 원래 원인은 어디에도 남지 않는다.
			log.error("필터 단계 오류를 처리하다 다시 오류가 났다. 원래 오류: {} {}", request.getMethod(),
					shorten(request.getRequestURI()), ex);
			throw failure;
		}
		if (resolved == null) {
			log.error("필터 단계 오류를 Problem Details로 변환하지 못했다: {} {}", request.getMethod(),
					shorten(request.getRequestURI()), ex);
			if (!response.isCommitted()) {
				response.sendError(HttpStatus.INTERNAL_SERVER_ERROR.value());
			}
		}
		return true;
	}

	// 쓰다 만 응답의 본문과 헤더를 버린다. 남겨 두면 버려진 응답의 Content-Length나 Retry-After가 오류 응답에 섞인다.
	// CORS 헤더만은 남긴다. 지우면 다른 출처의 프론트가 오류 응답을 읽지 못한다.
	private static void discardAbandonedResponse(HttpServletResponse response) {
		Map<String, List<String>> corsHeaders = new LinkedHashMap<>();
		for (String name : response.getHeaderNames()) {
			if (name.toLowerCase(Locale.ROOT).startsWith(CORS_HEADER_PREFIX) || HttpHeaders.VARY.equalsIgnoreCase(name)) {
				corsHeaders.put(name, List.copyOf(response.getHeaders(name)));
			}
		}
		response.reset();
		corsHeaders.forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
	}

	private static String shorten(String uri) {
		return (uri != null && uri.length() > 200) ? uri.substring(0, 200) + "…" : uri;
	}

}
