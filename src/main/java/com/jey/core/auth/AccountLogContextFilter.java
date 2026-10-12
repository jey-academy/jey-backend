package com.jey.core.auth;

import java.io.IOException;

import com.jey.core.auth.api.AuthenticatedUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 로그인한 요청이 남기는 로그 줄에 계정 ID가 찍히게 한다 (ADR-0028). 아이디와 이름은 넣지 않는다.
 *
 * <p>보안 필터들 가운데 로그인 정보를 읽을 수 있게 되는 바로 그 자리에서 돈다({@code SecurityConfig}).
 * CSRF 검사와 인증 검사보다 앞이라, 로그인한 사용자의 요청이 거기서 막혀도 그 로그에 계정 ID가 찍힌다.
 *
 * <p>넣기만 한다. 지우는 것은 가장 바깥의 필터({@code core.web.RequestIdFilter})가 요청이 끝날 때 한다.
 * 컨테이너가 오류 경로로 다시 보낸 요청에도 돈다. 그때는 처음의 로그 문맥이 이미 비워져 있다.
 */
class AccountLogContextFilter extends OncePerRequestFilter {

	private static final String MDC_KEY = "accountId";

	// 로그인은 했는데 누구인지 알 수 없는 로그인 정보일 때 넣는 표시.
	static final String UNKNOWN = "unknown";

	@Override
	protected boolean shouldNotFilterErrorDispatch() {
		return false;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String accountId = accountId(SecurityContextHolder.getContext().getAuthentication());
		if (accountId != null) {
			MDC.put(MDC_KEY, accountId);
		}
		chain.doFilter(request, response);
	}

	// 로그에 덧붙이는 값이라, 누구인지 알 수 없는 로그인 정보여도 요청을 멈추지 않는다(CurrentActor는 그때 예외를 던진다).
	// 비워 두면 로그인하지 않은 요청과 구분되지 않으므로 표시를 넣는다.
	private static String accountId(Authentication authentication) {
		if (authentication == null || !authentication.isAuthenticated()
				|| authentication instanceof AnonymousAuthenticationToken) {
			return null;
		}
		return (authentication.getPrincipal() instanceof AuthenticatedUser user) ? String.valueOf(user.id()) : UNKNOWN;
	}

}
