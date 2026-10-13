package com.jey.core.auth;

import java.util.OptionalLong;

import jakarta.servlet.http.HttpSession;

/**
 * 세션에 든 권한 판 번호 (ADR-0031). 그 세션을 만든 로그인이 본 계정의 번호다.
 * 로그인할 때 넣고, 본인이 비밀번호를 바꾸면 지금 세션의 것을 새 번호로 바꾸며, 요청마다 계정의 지금 번호와 비교한다.
 *
 * <p>세션 속성의 이름과 값의 타입(Long)을 여기 한 곳에서만 안다. 다른 타입으로 넣으면 읽는 쪽이 번호로 보지 않아
 * 그 세션은 다음 요청에서 끊긴다.
 */
final class SessionAuthVersion {

	// 이름을 바꾸면 그 전에 로그인된 세션은 번호가 없는 세션이 되어 모두 끊긴다.
	static final String ATTRIBUTE = "jey.auth.authVersion";

	private SessionAuthVersion() {
	}

	static void put(HttpSession session, long authVersion) {
		session.setAttribute(ATTRIBUTE, authVersion);
	}

	/** 번호가 없거나 번호로 읽을 수 없으면 빈 값이다. */
	static OptionalLong of(HttpSession session) {
		return (session.getAttribute(ATTRIBUTE) instanceof Long authVersion) ? OptionalLong.of(authVersion)
				: OptionalLong.empty();
	}

}
