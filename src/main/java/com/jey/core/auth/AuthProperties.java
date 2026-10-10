package com.jey.core.auth;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.jey.core.auth.api.UserRole;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 인증 설정({@code jey.auth.*}). 값이 잘못되면 서버가 뜨지 않는다. 잘못된 설정으로 조용히 뜨는 것보다 낫다.
 *
 * @param allowedOrigins 쿠키를 실은 요청을 허용할 프론트 출처({@code http(s)://호스트[:포트]}). {@code /api/**}에만 적용된다.
 * 비어 있으면 다른 출처의 요청을 모두 거부한다.
 * @param allowedOriginsRequired 허용 출처가 비어 있으면 서버 시작을 실패시킬지. 운영에서는 {@code true}
 * @param cookieDomain CSRF 쿠키의 도메인. 프론트와 API의 호스트가 다르면 프론트가 쿠키를 읽을 수 있게 공통 상위 도메인을 넣는다.
 * 세션 쿠키에는 적용하지 않는다(API 호스트 전용). 비우면 지정하지 않는다.
 * @param secureCookies 세션 쿠키와 CSRF 쿠키에 Secure 속성을 붙일지. 기본값은 {@code true}이고, HTTPS가 없는 로컬에서만 끈다.
 * @param sessionTimeout 역할별 세션 만료 시간(마지막 요청 기준). 없는 역할은 {@link #DEFAULT_SESSION_TIMEOUT}
 * @param sessionMaxLifetime 역할별 세션 최대 유지 시간(로그인 시점 기준). 계속 쓰고 있어도 이 시간이 지나면 다시 로그인해야 한다.
 * 없는 역할은 {@link #DEFAULT_SESSION_MAX_LIFETIME}
 * @param initialAdmin 계정이 하나도 없을 때 만들 첫 관리자
 */
@ConfigurationProperties("jey.auth")
record AuthProperties(List<String> allowedOrigins, @DefaultValue("false") boolean allowedOriginsRequired,
		String cookieDomain, @DefaultValue("true") boolean secureCookies, Map<UserRole, Duration> sessionTimeout,
		Map<UserRole, Duration> sessionMaxLifetime, InitialAdmin initialAdmin) {

	static final Duration DEFAULT_SESSION_TIMEOUT = Duration.ofHours(2);

	static final Duration DEFAULT_SESSION_MAX_LIFETIME = Duration.ofHours(12);

	// 단위를 빼먹은 "12"는 12밀리초로 읽힌다. 그런 값으로 뜨면 로그인하자마자 세션이 끊긴다.
	private static final Duration MIN_SESSION_DURATION = Duration.ofMinutes(1);

	AuthProperties {
		allowedOrigins = (allowedOrigins == null) ? List.of() : List.copyOf(allowedOrigins);
		allowedOrigins.forEach(AuthProperties::validateOrigin);
		// 환경변수가 "있지만 비어 있는" 경우다. 그대로 뜨면 프론트의 모든 요청이 조용히 거부된다.
		if (allowedOriginsRequired && allowedOrigins.isEmpty()) {
			throw new IllegalArgumentException("jey.auth.allowed-origins가 비어 있다. 프론트 주소를 넣어야 한다.");
		}
		cookieDomain = (cookieDomain == null || cookieDomain.isBlank()) ? null : cookieDomain.trim();
		sessionTimeout = validatedDurations(sessionTimeout, "session-timeout");
		sessionMaxLifetime = validatedDurations(sessionMaxLifetime, "session-max-lifetime");
		initialAdmin = (initialAdmin == null) ? new InitialAdmin(null, null, null, false) : initialAdmin;
		for (UserRole role : UserRole.values()) {
			Duration timeout = sessionTimeout.getOrDefault(role, DEFAULT_SESSION_TIMEOUT);
			Duration maxLifetime = sessionMaxLifetime.getOrDefault(role, DEFAULT_SESSION_MAX_LIFETIME);
			// 최대 유지 시간이 만료 시간보다 짧으면 만료 시간 설정이 의미가 없어진다. 둘을 바꿔 적은 실수일 가능성이 크다.
			if (maxLifetime.compareTo(timeout) < 0) {
				throw new IllegalArgumentException("jey.auth.session-max-lifetime." + role.name().toLowerCase()
						+ "(" + maxLifetime + ")는 session-timeout(" + timeout + ")보다 짧을 수 없다");
			}
		}
	}

	Duration sessionTimeoutFor(UserRole role) {
		return sessionTimeout.getOrDefault(role, DEFAULT_SESSION_TIMEOUT);
	}

	Duration sessionMaxLifetimeFor(UserRole role) {
		return sessionMaxLifetime.getOrDefault(role, DEFAULT_SESSION_MAX_LIFETIME);
	}

	boolean hasCookieDomain() {
		return cookieDomain != null;
	}

	// 환경변수가 없으면 "${AUTH_ALLOWED_ORIGINS}"라는 글자가 그대로 들어온다. 그 상태로 뜨면 프론트의 모든 요청이 조용히 거부된다.
	// "*"는 쿠키를 실은 요청과 함께 쓸 수 없어 요청 때마다 오류가 난다.
	// 브라우저가 보내는 Origin과 글자 그대로 비교되므로, 경로·계정 정보·쿼리가 붙은 값은 절대 일치하지 않는다.
	// 오류 문구에는 값을 그대로 싣지 않는다. 다른 설정값(계정 정보가 든 주소 등)을 잘못 넣었을 때 로그에 남지 않게 한다.
	private static void validateOrigin(String origin) {
		if (origin == null || origin.isBlank()) {
			throw new IllegalArgumentException("jey.auth.allowed-origins에 빈 값이 있다");
		}
		if (origin.contains("${")) {
			throw new IllegalArgumentException("jey.auth.allowed-origins의 환경변수가 설정되지 않았다");
		}
		URI uri;
		try {
			uri = URI.create(origin.trim());
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("jey.auth.allowed-origins의 형식이 잘못됐다. http(s)://호스트[:포트] 형식이어야 한다");
		}
		boolean httpScheme = "http".equals(uri.getScheme()) || "https".equals(uri.getScheme());
		String host = uri.getHost();
		boolean hasPath = uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath());
		boolean hasExtra = uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null;
		if (!httpScheme || host == null || host.contains("*") || host.endsWith(".") || hasPath || hasExtra) {
			throw new IllegalArgumentException("jey.auth.allowed-origins는 http(s)://호스트[:포트] 형식이어야 한다"
					+ ((host != null) ? ": " + uri.getScheme() + "://" + host : ""));
		}
	}

	private static Map<UserRole, Duration> validatedDurations(Map<UserRole, Duration> durations, String property) {
		if (durations == null) {
			return Map.of();
		}
		durations.forEach((role, duration) -> {
			if (duration == null || duration.compareTo(MIN_SESSION_DURATION) < 0) {
				throw new IllegalArgumentException("jey.auth." + property + "." + role.name().toLowerCase()
						+ "는 1분 이상이어야 한다(단위를 붙였는지 확인): " + duration);
			}
		});
		return Map.copyOf(durations);
	}

	/**
	 * @param required 계정이 하나도 없는데 설정값도 없으면 서버 시작을 실패시킬지. 운영에서는 {@code true}
	 */
	record InitialAdmin(String loginId, String password, String name, @DefaultValue("false") boolean required) {

		private static final int MIN_PASSWORD_LENGTH = 10;

		// BCrypt는 72바이트까지만 받는다. 한글은 한 글자가 3바이트다.
		private static final int MAX_PASSWORD_BYTES = 72;

		boolean hasLoginId() {
			return hasText(loginId);
		}

		boolean isConfigured() {
			return hasText(loginId) && hasText(password);
		}

		// 아이디와 비밀번호 중 하나만 있는 경우. 환경변수 하나를 빠뜨린 것이다.
		boolean isHalfConfigured() {
			return hasText(loginId) != hasText(password);
		}

		boolean hasAcceptablePassword() {
			return hasText(password) && password.length() >= MIN_PASSWORD_LENGTH
					&& password.getBytes(StandardCharsets.UTF_8).length <= MAX_PASSWORD_BYTES;
		}

		private static boolean hasText(String value) {
			return value != null && !value.isBlank();
		}

		// 비밀번호가 로그에 찍히지 않게 한다.
		@Override
		public String toString() {
			return "InitialAdmin[loginId=" + loginId + "]";
		}

	}

}
