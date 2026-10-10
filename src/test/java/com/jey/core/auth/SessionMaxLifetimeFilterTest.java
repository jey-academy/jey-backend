package com.jey.core.auth;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import static org.assertj.core.api.Assertions.assertThat;

// 세션 만료는 마지막 요청 기준이라, 화면이 주기적으로 요청을 보내면 끝없이 연장된다. 이 필터가 로그인 시점부터의 상한을 둔다.
class SessionMaxLifetimeFilterTest {

	private static final Instant EXPIRES_AT = Instant.parse("2026-11-03T12:00:00Z");

	@Test
	void 최대_유지_시각_전에는_세션을_그대로_둔다() throws Exception {
		MockHttpSession session = sessionExpiringAt(EXPIRES_AT);

		MockFilterChain chain = runFilterAt(EXPIRES_AT.minusMillis(1), session);

		assertThat(session.isInvalid()).isFalse();
		assertThat(chain.getRequest()).isNotNull();
	}

	@Test
	void 최대_유지_시각이_되면_세션을_끊고_요청은_계속_진행한다() throws Exception {
		MockHttpSession session = sessionExpiringAt(EXPIRES_AT);

		MockFilterChain chain = runFilterAt(EXPIRES_AT, session);

		assertThat(session.isInvalid()).isTrue();
		// 요청을 막지 않고 넘긴다. 세션이 없어졌으므로 뒤에서 인증 없는 요청으로 처리돼 401이 된다.
		assertThat(chain.getRequest()).isNotNull();
	}

	@Test
	void 세션이_없으면_만들지_않는다() throws Exception {
		var request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
		var chain = new MockFilterChain();

		new SessionMaxLifetimeFilter(clockAt(EXPIRES_AT)).doFilter(request, new MockHttpServletResponse(), chain);

		assertThat(request.getSession(false)).isNull();
		assertThat(chain.getRequest()).isNotNull();
	}

	@Test
	void 최대_유지_시각이_적히지_않은_세션은_건드리지_않는다() throws Exception {
		var session = new MockHttpSession();

		runFilterAt(EXPIRES_AT.plusSeconds(3600), session);

		assertThat(session.isInvalid()).isFalse();
	}

	private static MockHttpSession sessionExpiringAt(Instant expiresAt) {
		var session = new MockHttpSession();
		session.setAttribute(SessionMaxLifetimeFilter.EXPIRES_AT_ATTRIBUTE, expiresAt.toEpochMilli());
		return session;
	}

	private static MockFilterChain runFilterAt(Instant now, MockHttpSession session) throws Exception {
		var request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
		request.setSession(session);
		var chain = new MockFilterChain();
		new SessionMaxLifetimeFilter(clockAt(now)).doFilter(request, new MockHttpServletResponse(), chain);
		return chain;
	}

	private static Clock clockAt(Instant now) {
		return Clock.fixed(now, ZoneId.of("Asia/Seoul"));
	}

}
