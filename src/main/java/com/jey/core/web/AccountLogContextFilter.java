package com.jey.core.web;

import java.io.IOException;

import com.jey.core.auth.api.AuthenticatedUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 로그인한 요청이 남기는 로그 줄에 계정 ID가 찍히게 한다 (ADR-0028). 아이디와 이름은 넣지 않는다.
 *
 * <p>넣기만 한다. 지우는 것은 가장 바깥의 {@link RequestIdFilter}가 요청이 끝날 때 한다.
 * 보안 필터가 막은 요청(로그인 없음, CSRF 거부)은 여기까지 오지 않으므로 계정 ID가 없다.
 */
@Component
class AccountLogContextFilter extends OncePerRequestFilter implements Ordered {

	static final String MDC_KEY = "accountId";

	// 보안 필터(-100) 바로 뒤. 로그인 정보를 읽을 수 있는 가장 앞자리다.
	@Override
	public int getOrder() {
		return -99;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		// 로그에 덧붙이는 값이라, 누구인지 알 수 없는 로그인 정보여도 요청을 멈추지 않고 비워 둔다.
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
			MDC.put(MDC_KEY, String.valueOf(user.id()));
		}
		chain.doFilter(request, response);
	}

}
