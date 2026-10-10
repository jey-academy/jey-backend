package com.jey;

import java.util.Arrays;
import java.util.stream.Stream;

import jakarta.servlet.http.Cookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 테스트 요청에 CSRF 토큰을 싣는다. 프론트와 같은 방식이다: 쿠키(XSRF-TOKEN)로 받은 값을 헤더(X-XSRF-TOKEN)에 넣는다.
 *
 * <p>spring-security-test의 {@code csrf()}는 쓰지 않는다. 그 도구는 필터 안의 토큰 저장소를 테스트용으로 바꿔 끼우고,
 * 그 상태가 같은 스프링 컨텍스트를 쓰는 다른 테스트에 남아서 실제 쿠키 방식이 동작하지 않게 된다.
 */
public final class TestCsrf {

	public static final String COOKIE_NAME = "XSRF-TOKEN";

	public static final String HEADER_NAME = "X-XSRF-TOKEN";

	private static final String CSRF_ENDPOINT = "/api/v1/auth/csrf";

	private TestCsrf() {
	}

	/** 토큰을 새로 받아 요청의 쿠키와 헤더에 싣는다. */
	public static RequestPostProcessor csrfToken(MockMvc mockMvc) throws Exception {
		Cookie cookie = issue(mockMvc);
		return request -> {
			Cookie[] existing = (request.getCookies() == null) ? new Cookie[0] : request.getCookies();
			request.setCookies(Stream.concat(Arrays.stream(existing), Stream.of(cookie)).toArray(Cookie[]::new));
			request.addHeader(HEADER_NAME, cookie.getValue());
			return request;
		};
	}

	/** 토큰 쿠키만 받는다. */
	public static Cookie issue(MockMvc mockMvc) throws Exception {
		Cookie cookie = mockMvc.perform(get(CSRF_ENDPOINT)).andReturn().getResponse().getCookie(COOKIE_NAME);
		if (cookie == null) {
			throw new IllegalStateException(CSRF_ENDPOINT + " 응답에 " + COOKIE_NAME + " 쿠키가 없다");
		}
		return cookie;
	}

}
