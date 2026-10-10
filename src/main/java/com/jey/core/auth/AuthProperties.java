package com.jey.core.auth;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.jey.core.auth.api.UserRole;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 인증 설정({@code jey.auth.*}).
 *
 * @param allowedOrigins 자격 증명(쿠키)을 실은 요청을 허용할 프론트 출처. 비어 있으면 다른 출처의 요청을 모두 거부한다.
 * @param cookieDomain CSRF 쿠키의 도메인. 프론트(상위 도메인)가 API(서브도메인)가 준 쿠키를 읽어야 할 때 상위 도메인을 넣는다.
 * 비우면 지정하지 않는다.
 * @param secureCookies 세션 쿠키와 CSRF 쿠키에 Secure 속성을 붙일지. HTTPS로 서비스하는 운영 환경에서는 {@code true}
 * @param sessionTimeout 역할별 세션 만료 시간(마지막 요청 기준). 없는 역할은 {@link #DEFAULT_SESSION_TIMEOUT}
 * @param initialAdmin 계정이 하나도 없을 때 만들 첫 관리자
 */
@ConfigurationProperties("jey.auth")
record AuthProperties(List<String> allowedOrigins, String cookieDomain, boolean secureCookies,
		Map<UserRole, Duration> sessionTimeout, InitialAdmin initialAdmin) {

	static final Duration DEFAULT_SESSION_TIMEOUT = Duration.ofHours(2);

	AuthProperties {
		allowedOrigins = (allowedOrigins == null) ? List.of() : List.copyOf(allowedOrigins);
		sessionTimeout = (sessionTimeout == null) ? Map.of() : Map.copyOf(sessionTimeout);
	}

	Duration sessionTimeoutFor(UserRole role) {
		return sessionTimeout.getOrDefault(role, DEFAULT_SESSION_TIMEOUT);
	}

	boolean hasCookieDomain() {
		return cookieDomain != null && !cookieDomain.isBlank();
	}

	record InitialAdmin(String loginId, String password, String name) {

		boolean isConfigured() {
			return loginId != null && !loginId.isBlank() && password != null && !password.isBlank();
		}

		// 비밀번호가 로그에 찍히지 않게 한다.
		@Override
		public String toString() {
			return "InitialAdmin[loginId=" + loginId + "]";
		}

	}

}
