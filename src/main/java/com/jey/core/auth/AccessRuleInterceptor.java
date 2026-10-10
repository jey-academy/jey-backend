package com.jey.core.auth;

import com.jey.core.auth.api.AccessErrorCode;
import com.jey.core.shared.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

// 컨트롤러 메서드를 부르기 직전에 접근 규칙을 확인한다. 로그인 여부는 보안 필터가 이미 걸렀고, 여기서는 역할을 본다.
// 예외는 컨트롤러 예외와 같은 경로(core.web)로 Problem Details가 된다. 역할 거부의 로그도 거기서 남긴다.
class AccessRuleInterceptor implements HandlerInterceptor {

	private static final Logger log = LoggerFactory.getLogger(AccessRuleInterceptor.class);

	private static final int MAX_LOGGED_URI_LENGTH = 200;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		// 정적 자원 처리기와 라이브러리가 등록한 컨트롤러는 대상이 아니다.
		if (!(handler instanceof HandlerMethod handlerMethod) || !AccessRule.appliesTo(handlerMethod)) {
			return true;
		}
		// 규칙의 종류가 늘면 여기서 컴파일이 멈춘다. 새 규칙이 아무 검사 없이 통과하지 않게 default를 두지 않는다.
		switch (AccessRule.of(handlerMethod)) {
			case AccessRule.NoLogin noLogin -> {
			}
			case AccessRule.AnyLoggedIn anyLoggedIn -> requireLogin(request);
			case AccessRule.Roles roles -> {
				if (!roles.allows(requireLogin(request))) {
					throw new BusinessException(AccessErrorCode.ROLE_NOT_ALLOWED);
				}
			}
		}
		return true;
	}

	// 여기서 걸리는 것은 보안 설정에서는 열어 두고 표시는 로그인이 필요하다고 한 경우뿐이다. 닫힌 쪽으로 답한다.
	// 설정과 표시가 어긋난 것이므로 흔적을 남긴다.
	private static Authentication requireLogin(HttpServletRequest request) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()
				|| authentication instanceof AnonymousAuthenticationToken) {
			log.warn("보안 설정은 로그인 없이 열려 있는데 접근 규칙은 로그인을 요구한다: {} {}", request.getMethod(),
					shorten(request.getRequestURI()));
			throw new InsufficientAuthenticationException("로그인이 필요한 API");
		}
		return authentication;
	}

	private static String shorten(String uri) {
		return (uri != null && uri.length() > MAX_LOGGED_URI_LENGTH) ? uri.substring(0, MAX_LOGGED_URI_LENGTH) + "…"
				: uri;
	}

}
