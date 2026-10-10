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
// 예외는 컨트롤러 예외와 같은 경로(core.web)로 Problem Details가 된다.
class AccessRuleInterceptor implements HandlerInterceptor {

	private static final Logger log = LoggerFactory.getLogger(AccessRuleInterceptor.class);

	private static final int MAX_LOGGED_URI_LENGTH = 200;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		// 정적 자원, CORS 사전 요청, 라이브러리가 등록한 컨트롤러는 대상이 아니다.
		if (!(handler instanceof HandlerMethod handlerMethod) || !AccessRule.appliesTo(handlerMethod)) {
			return true;
		}
		AccessRule rule = AccessRule.of(handlerMethod);
		if (rule instanceof AccessRule.NoLogin) {
			return true;
		}
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		// 보안 설정에서는 열어 두고 표시는 로그인이 필요하다고 한 경우다. 닫힌 쪽으로 답한다.
		if (authentication == null || !authentication.isAuthenticated()
				|| authentication instanceof AnonymousAuthenticationToken) {
			throw new InsufficientAuthenticationException("로그인이 필요한 API");
		}
		if (rule instanceof AccessRule.Roles roles && !roles.allows(authentication)) {
			// 이름은 계정 ID다(AuthenticatedUser#getName). 아이디와 실명은 남기지 않는다.
			log.warn("역할 거부: account={} {} {} 허용={}", authentication.getName(), request.getMethod(),
					shorten(request.getRequestURI()), roles.roles());
			throw new BusinessException(AccessErrorCode.ROLE_NOT_ALLOWED);
		}
		return true;
	}

	private static String shorten(String uri) {
		return (uri != null && uri.length() > MAX_LOGGED_URI_LENGTH) ? uri.substring(0, MAX_LOGGED_URI_LENGTH) + "…"
				: uri;
	}

}
