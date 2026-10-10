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
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 로그인한 지 최대 유지 시간이 지난 세션을 끊는다.
 * 세션 만료는 마지막 요청 기준이라, 화면이 주기적으로 요청을 보내면 세션이 끝없이 연장된다. 이 필터가 그 상한을 둔다.
 * 끊긴 요청은 인증 없는 요청으로 처리돼 401을 받는다.
 */
final class SessionMaxLifetimeFilter extends OncePerRequestFilter {

	/** 세션이 끊길 시각(epoch millis). 로그인할 때 LoginService가 넣는다. */
	static final String EXPIRES_AT_ATTRIBUTE = "jey.auth.sessionExpiresAt";

	private static final Logger log = LoggerFactory.getLogger(SessionMaxLifetimeFilter.class);

	private final Clock clock;

	SessionMaxLifetimeFilter(Clock clock) {
		this.clock = clock;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		HttpSession session = request.getSession(false);
		if (session != null && session.getAttribute(EXPIRES_AT_ATTRIBUTE) instanceof Long expiresAt
				&& clock.millis() >= expiresAt) {
			log.info("최대 유지 시간이 지난 세션을 끊는다");
			session.invalidate();
		}
		chain.doFilter(request, response);
	}

}
