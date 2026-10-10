package com.jey.core.auth;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import static org.assertj.core.api.Assertions.assertThat;

// 세션 만료는 마지막 요청 기준이라, 화면이 주기적으로 요청을 보내면 끝없이 연장된다. 이 필터가 로그인 시점부터의 상한을 둔다.
// 실제 요청 흐름에 연결돼 있는지는 AuthLoginTest가 확인한다.
@ExtendWith(OutputCaptureExtension.class)
class SessionMaxLifetimeFilterTest {

	private static final Instant EXPIRES_AT = Instant.parse("2026-11-03T12:00:00Z");

	@Test
	void 최대_유지_시각_전에는_세션을_그대로_둔다() throws Exception {
		MockHttpSession session = loggedInSession(EXPIRES_AT.toEpochMilli());

		MockFilterChain chain = runFilterAt(EXPIRES_AT.minusMillis(1), session);

		assertThat(session.isInvalid()).isFalse();
		assertThat(chain.getRequest()).isNotNull();
	}

	@Test
	void 최대_유지_시각이_되면_세션을_끊고_요청은_계속_진행한다(CapturedOutput output) throws Exception {
		MockHttpSession session = loggedInSession(EXPIRES_AT.toEpochMilli());

		MockFilterChain chain = runFilterAt(EXPIRES_AT, session);

		assertThat(session.isInvalid()).isTrue();
		// 요청을 막지 않고 넘긴다. 세션이 없어졌으므로 뒤에서 인증 없는 요청으로 처리된다.
		assertThat(chain.getRequest()).isNotNull();
		// 누구의 세션인지는 계정 ID로만 남긴다.
		assertThat(output).contains("최대 유지 시간이 지난 세션을 끊는다: userId=42");
	}

	@Test
	void 세션이_없으면_만들지_않는다() throws Exception {
		var request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
		var chain = new MockFilterChain();

		new SessionMaxLifetimeFilter(clockAt(EXPIRES_AT)).doFilter(request, new MockHttpServletResponse(), chain);

		assertThat(request.getSession(false)).isNull();
		assertThat(chain.getRequest()).isNotNull();
	}

	// 시각을 읽지 못했다고 통과시키면 상한이 조용히 사라진다.
	@Test
	void 로그인된_세션에_최대_유지_시각이_없으면_끊는다(CapturedOutput output) throws Exception {
		MockHttpSession session = loggedInSession(null);

		runFilterAt(EXPIRES_AT.minusSeconds(3600), session);

		assertThat(session.isInvalid()).isTrue();
		assertThat(output).contains("최대 유지 시각이 없는 세션을 끊는다: userId=42");
	}

	// Long으로 넣어야 한다. 다른 타입으로 넣는 실수를 하면 필터가 그 값을 시각으로 읽지 않는다.
	@Test
	void 최대_유지_시각이_Long이_아니면_끊는다() throws Exception {
		MockHttpSession session = loggedInSession(EXPIRES_AT.plusSeconds(3600));

		runFilterAt(EXPIRES_AT, session);

		assertThat(session.isInvalid()).isTrue();
	}

	// 세션은 로그인할 때만 만들어진다. 로그인 정보가 없거나 읽을 수 없는 세션은 정상이 아니다.
	@Test
	void 로그인_정보가_없는_세션은_끊는다(CapturedOutput output) throws Exception {
		var session = new MockHttpSession();
		session.setAttribute(SessionMaxLifetimeFilter.EXPIRES_AT_ATTRIBUTE, EXPIRES_AT.toEpochMilli());

		runFilterAt(EXPIRES_AT.minusSeconds(3600), session);

		assertThat(session.isInvalid()).isTrue();
		assertThat(output).contains("로그인 정보가 없는 세션을 끊는다");
	}

	private static MockHttpSession loggedInSession(Object expiresAt) {
		var user = new AuthenticatedUser(42L, "desk", "김직원", UserRole.STAFF, 1L);
		var session = new MockHttpSession();
		session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
				new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(user, null, List.of())));
		if (expiresAt != null) {
			session.setAttribute(SessionMaxLifetimeFilter.EXPIRES_AT_ATTRIBUTE, expiresAt);
		}
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
