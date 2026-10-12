package com.jey.core.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청마다 ID를 정해 로그 문맥(MDC)과 응답 헤더에 싣는다 (ADR-0028).
 * 그 요청이 남기는 로그 줄마다 같은 ID가 찍히고, 오류 응답의 본문에도 실린다({@link GlobalExceptionHandler}).
 *
 * <p>앞단이 보낸 {@code X-Request-Id}가 형식에 맞으면 그대로 쓴다. 맞지 않으면 버리고 새로 만든다.
 * 요청을 구분하려는 값 때문에 요청을 거부하지는 않는다.
 *
 * <p>컨테이너가 오류 경로로 다시 보낸 요청에도 돈다. 그때는 처음에 정한 ID를 다시 쓴다.
 */
@Component
class RequestIdFilter extends OncePerRequestFilter implements Ordered {

	private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

	static final String HEADER = "X-Request-Id";

	private static final String MDC_KEY = "requestId";

	// 이 속성에 쓰는 곳은 이 필터뿐이다. 그래서 여기서 읽은 값은 언제나 형식에 맞는 ID다.
	private static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";

	// 줄바꿈이나 긴 값이 로그에 그대로 찍히지 않게 좁힌다. Nginx의 $request_id(16진수 32자)가 여기에 든다.
	private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{8,64}");

	/**
	 * @return 이 요청의 ID. 이 필터를 거치지 않은 요청이면 {@code null}
	 */
	static String of(HttpServletRequest request) {
		return (request.getAttribute(ATTRIBUTE) instanceof String id) ? id : null;
	}

	/**
	 * @return 이 요청의 ID. 이 필터를 거치지 않은 요청이면 {@code null}
	 */
	static String of(WebRequest request) {
		return (request.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) instanceof String id) ? id : null;
	}

	// 오류를 잡는 필터(FilterExceptionFilter)보다도 바깥이어야 그 필터가 남기는 로그에 ID가 찍힌다.
	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE;
	}

	// 필터가 sendError로 끝낸 요청은 컨테이너가 오류 경로로 다시 보낸다. 그때는 처음의 필터가 이미 끝나 로그 문맥이 비어 있다.
	@Override
	protected boolean shouldNotFilterErrorDispatch() {
		return false;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String id = resolve(request);
		request.setAttribute(ATTRIBUTE, id);
		response.setHeader(HEADER, id);
		MDC.put(MDC_KEY, id);
		try {
			chain.doFilter(request, response);
		}
		finally {
			// 스레드는 다음 요청이 다시 쓴다. 안쪽에서 넣은 값(계정 ID)까지 모두 지운다.
			MDC.clear();
		}
	}

	private static String resolve(HttpServletRequest request) {
		String existing = of(request);
		if (existing != null) {
			return existing;
		}
		String incoming = request.getHeader(HEADER);
		if (incoming == null) {
			return generate();
		}
		if (FORMAT.matcher(incoming).matches()) {
			return incoming;
		}
		// 앞단이 형식 밖의 값을 보내면 모든 요청이 새 ID를 받아, 앞단의 로그와 이 서버의 로그가 이어지지 않는다.
		// 그것을 알아챌 수 있게 흔적을 남기되, 로그인 없이도 보낼 수 있는 값이라 평소에는 찍지 않는 수준(DEBUG)으로 남긴다.
		// 값은 찍지 않는다. 찍으면 형식을 좁힌 뜻이 없다.
		log.debug("형식에 맞지 않는 {}를 버리고 새로 만든다: length={}", HEADER, incoming.length());
		return generate();
	}

	private static String generate() {
		return UUID.randomUUID().toString().replace("-", "");
	}

}
