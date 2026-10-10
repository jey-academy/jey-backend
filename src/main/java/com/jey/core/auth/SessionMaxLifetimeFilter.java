package com.jey.core.auth;

import java.io.IOException;
import java.time.Clock;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 로그인한 지 최대 유지 시간이 지난 세션을 끊는다.
 * 세션 만료는 마지막 요청 기준이라, 화면이 주기적으로 요청을 보내면 세션이 끝없이 연장된다. 이 필터가 그 상한을 둔다.
 *
 * <p>세션은 로그인할 때만 만들어지고, 그때 로그인 정보와 최대 유지 시각이 함께 들어간다.
 * 둘 중 하나라도 없거나 읽을 수 없는 세션은 정상이 아니므로 끊는다. 시각을 읽지 못했다고 통과시키면 상한이 조용히 사라진다.
 *
 * <p>끊은 뒤에도 요청은 계속 진행한다. 세션이 없어졌으므로 인증이 필요한 경로라면 401을 받는다.
 */
final class SessionMaxLifetimeFilter extends OncePerRequestFilter {

	/** 세션이 끊길 시각(epoch millis, Long). 로그인할 때 LoginService가 넣는다. */
	static final String EXPIRES_AT_ATTRIBUTE = "jey.auth.sessionExpiresAt";

	private static final String SECURITY_CONTEXT_ATTRIBUTE = HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;

	private static final Logger log = LoggerFactory.getLogger(SessionMaxLifetimeFilter.class);

	private final Clock clock;

	SessionMaxLifetimeFilter(Clock clock) {
		this.clock = clock;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		HttpSession session = request.getSession(false);
		if (session != null) {
			terminateIfNotAllowed(session);
		}
		chain.doFilter(request, response);
	}

	private void terminateIfNotAllowed(HttpSession session) {
		Object securityContext = session.getAttribute(SECURITY_CONTEXT_ATTRIBUTE);
		Object expiresAt = session.getAttribute(EXPIRES_AT_ATTRIBUTE);
		if (!(securityContext instanceof SecurityContext context) || context.getAuthentication() == null) {
			log.warn("로그인 정보가 없는 세션을 끊는다");
			session.invalidate();
		}
		else if (!(expiresAt instanceof Long expiresAtMillis)) {
			log.warn("최대 유지 시각이 없는 세션을 끊는다: userId={}", context.getAuthentication().getName());
			session.invalidate();
		}
		else if (clock.millis() >= expiresAtMillis) {
			log.info("최대 유지 시간이 지난 세션을 끊는다: userId={}", context.getAuthentication().getName());
			session.invalidate();
		}
	}

}
