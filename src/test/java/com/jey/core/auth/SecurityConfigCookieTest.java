package com.jey.core.auth;

import java.util.List;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.session.web.http.CookieSerializer.CookieValue;

import static org.assertj.core.api.Assertions.assertThat;

// Secure 쿠키는 기본값이 켜짐이고 로컬·테스트 프로필에서만 끈다. 쿠키 도메인은 운영에서만 넣는다.
// 대부분의 통합 테스트는 그 값으로 돌지 않으므로, 설정값이 쿠키에 반영되는지를 여기서 직접 확인한다.
// 실제로 뜬 서버가 이 설정을 쓰는지는 InitialAdminLoginTest가 확인한다.
class SecurityConfigCookieTest {

	private final SecurityConfig config = new SecurityConfig();

	@Test
	void 운영_설정이면_세션_쿠키에_Secure가_붙는다() {
		String cookie = sessionCookie(properties(true, null));

		assertThat(cookie).startsWith("SESSION=").contains("Secure").contains("HttpOnly").contains("SameSite=Lax");
	}

	@Test
	void 로컬_설정이면_세션_쿠키에_Secure가_없다() {
		String cookie = sessionCookie(properties(false, null));

		assertThat(cookie).doesNotContain("Secure").contains("HttpOnly").contains("SameSite=Lax");
	}

	// 세션 쿠키는 API 호스트 전용이다. 쿠키 도메인 설정은 CSRF 쿠키에만 적용한다.
	@Test
	void 세션_쿠키에는_쿠키_도메인_설정을_적용하지_않는다() {
		assertThat(sessionCookie(properties(true, "jey.example"))).doesNotContain("Domain=");
	}

	// 프론트가 JavaScript로 읽어야 하므로 HttpOnly가 아니다.
	@Test
	void CSRF_쿠키는_설정에_따라_Secure와_Domain이_붙는다() {
		Cookie cookie = csrfCookie(properties(true, "jey.example"));

		assertThat(cookie.getSecure()).isTrue();
		assertThat(cookie.getDomain()).isEqualTo("jey.example");
		assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
		assertThat(cookie.isHttpOnly()).isFalse();
	}

	@Test
	void 설정이_없으면_CSRF_쿠키에_Secure와_Domain이_없다() {
		Cookie cookie = csrfCookie(properties(false, " "));

		assertThat(cookie.getSecure()).isFalse();
		assertThat(cookie.getDomain()).isNull();
		assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
	}

	private String sessionCookie(AuthProperties properties) {
		var response = new MockHttpServletResponse();
		config.sessionCookieSerializer(properties)
				.writeCookieValue(new CookieValue(new MockHttpServletRequest(), response, "session-id"));
		return response.getHeader(HttpHeaders.SET_COOKIE);
	}

	private Cookie csrfCookie(AuthProperties properties) {
		var request = new MockHttpServletRequest();
		var response = new MockHttpServletResponse();
		CsrfTokenRepository repository = config.csrfTokenRepository(properties);
		repository.saveToken(repository.generateToken(request), request, response);
		Cookie cookie = response.getCookie("XSRF-TOKEN");
		assertThat(cookie).as("CSRF 쿠키").isNotNull();
		return cookie;
	}

	private static AuthProperties properties(boolean secureCookies, String cookieDomain) {
		return new AuthProperties(List.of(), false, cookieDomain, secureCookies, null, null, null);
	}

}
