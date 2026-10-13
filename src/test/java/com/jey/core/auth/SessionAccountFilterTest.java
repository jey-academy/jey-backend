package com.jey.core.auth;

import java.util.List;
import java.util.Optional;

import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.auth.domain.User;
import com.jey.core.auth.domain.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// 계정을 바꾼 쪽이 세션을 치우지 못했어도, 그 세션의 다음 요청은 여기서 막힌다 (ADR-0031).
// 실제 요청 흐름에 연결돼 있는지는 SessionAccountCheckTest가 확인한다.
@ExtendWith(OutputCaptureExtension.class)
class SessionAccountFilterTest {

	private static final long USER_ID = 42L;

	private final UserRepository users = mock(UserRepository.class);

	private final SessionAccountFilter filter = new SessionAccountFilter(users);

	@Test
	void 로그인할_때와_같은_계정이면_세션을_그대로_둔다() throws Exception {
		User account = account();
		MockHttpSession session = sessionOf(account, account.getAuthVersion());

		MockFilterChain chain = run(session);

		assertThat(session.isInvalid()).isFalse();
		assertThat(chain.getRequest()).isNotNull();
	}

	@Test
	void 차단된_계정의_세션은_끊고_요청은_계속_진행한다(CapturedOutput output) throws Exception {
		User account = account();
		MockHttpSession session = sessionOf(account, account.getAuthVersion());
		account.disable();

		MockFilterChain chain = run(session);

		assertThat(session.isInvalid()).isTrue();
		// 요청을 막지 않고 넘긴다. 세션이 없어졌으므로 뒤에서 인증 없는 요청으로 처리된다.
		assertThat(chain.getRequest()).isNotNull();
		// 누구의 세션인지는 계정 ID로만 남긴다. 왜 끊었는지 가릴 수 있게 차단 여부와 두 번호를 함께 남긴다.
		assertThat(output).contains(
				"계정이 차단됐거나 바뀐 뒤에 남은 세션을 끊는다: userId=42 active=false sessionAuthVersion=0 accountAuthVersion=1");
	}

	// 번호를 올리지 않는 길로 계정이 바뀌었다(DB를 직접 고쳤다). 막기는 하되, 있어서는 안 되는 일이라 경고로 남긴다.
	@Test
	void 번호는_같은데_지점이_세션과_다르면_끊고_경고를_남긴다(CapturedOutput output) throws Exception {
		User account = account();
		MockHttpSession session = sessionOf(account, account.getAuthVersion());
		ReflectionTestUtils.setField(account, "campusId", 2L);

		run(session);

		assertThat(session.isInvalid()).isTrue();
		assertThat(output).contains("권한 판 번호는 같은데 역할이나 소속이 세션과 다른 세션을 끊는다: userId=42 authVersion=0")
			.doesNotContain("계정이 차단됐거나 바뀐 뒤에 남은 세션을 끊는다");
	}

	@Test
	void 역할이나_지점이_바뀐_계정의_옛_세션은_끊는다() throws Exception {
		User moved = account();
		MockHttpSession beforeMove = sessionOf(moved, moved.getAuthVersion());
		moved.changeAffiliation(UserRole.STAFF, 2L);
		User promoted = account();
		MockHttpSession beforePromotion = sessionOf(promoted, promoted.getAuthVersion());
		promoted.changeAffiliation(UserRole.ADMIN, null);

		run(beforeMove);
		run(beforePromotion);

		assertThat(beforeMove.isInvalid()).as("지점이 바뀜").isTrue();
		assertThat(beforePromotion.isInvalid()).as("역할이 바뀜").isTrue();
	}

	// 세션에 든 역할과 지점은 그대로다. 번호만 다르다.
	@Test
	void 비밀번호가_바뀐_계정의_옛_세션은_끊는다() throws Exception {
		User account = account();
		MockHttpSession session = sessionOf(account, account.getAuthVersion());
		account.changePassword("{noop}another-hash");

		run(session);

		assertThat(session.isInvalid()).isTrue();
	}

	@Test
	void 이름만_바뀐_계정의_세션은_그대로_둔다() throws Exception {
		User account = account();
		MockHttpSession session = sessionOf(account, account.getAuthVersion());
		account.rename("김데스크");

		run(session);

		assertThat(session.isInvalid()).isFalse();
	}

	// 번호를 읽지 못했다고 통과시키면 이 확인이 조용히 사라진다.
	@Test
	void 권한_판_번호가_없는_세션은_끊는다(CapturedOutput output) throws Exception {
		User account = account();
		MockHttpSession session = sessionOf(account, null);

		run(session);

		assertThat(session.isInvalid()).isTrue();
		assertThat(output).contains("권한 판 번호가 없는 세션을 끊는다: userId=42");
		// 번호가 없으면 계정을 읽을 것도 없다.
		verifyNoInteractions(users);
	}

	// Long으로 넣어야 한다. 다른 타입으로 넣는 실수를 하면 필터가 그 값을 번호로 읽지 않는다.
	@Test
	void 권한_판_번호가_Long이_아니면_끊는다() throws Exception {
		User account = account();
		MockHttpSession session = sessionOf(account, 0);

		run(session);

		assertThat(session.isInvalid()).isTrue();
	}

	@Test
	void 계정이_없는_세션은_끊는다(CapturedOutput output) throws Exception {
		MockHttpSession session = sessionOf(account(), 0L);
		when(users.findById(USER_ID)).thenReturn(Optional.empty());

		run(session);

		assertThat(session.isInvalid()).isTrue();
		assertThat(output).contains("계정이 없는 세션을 끊는다: userId=42");
	}

	// 통과시키면 그동안 차단이 풀리고, 세션을 끊으면 장애가 "로그인이 풀렸다"로 보인다.
	@Test
	void 계정을_읽지_못하면_요청을_멈추고_세션은_남긴다() {
		User account = account();
		MockHttpSession session = sessionOf(account, account.getAuthVersion());
		var failure = new DataAccessResourceFailureException("DB 장애");
		when(users.findById(USER_ID)).thenThrow(failure);
		var request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
		request.setSession(session);
		var chain = new MockFilterChain();

		assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), chain)).isSameAs(failure);

		assertThat(session.isInvalid()).isFalse();
		assertThat(chain.getRequest()).as("뒤의 필터로 넘기지 않는다").isNull();
	}

	@Test
	void 세션이_없으면_만들지_않고_계정도_읽지_않는다() throws Exception {
		var request = new MockHttpServletRequest("GET", "/api/v1/auth/csrf");
		var chain = new MockFilterChain();

		filter.doFilter(request, new MockHttpServletResponse(), chain);

		assertThat(request.getSession(false)).isNull();
		assertThat(chain.getRequest()).isNotNull();
		verifyNoInteractions(users);
	}

	// 로그인 정보가 없는 세션은 앞의 SessionMaxLifetimeFilter가 끊는다. 여기서는 계정을 읽지 않고 넘긴다.
	@Test
	void 로그인_정보가_없는_세션은_건드리지_않는다() throws Exception {
		var session = new MockHttpSession();

		MockFilterChain chain = run(session);

		assertThat(session.isInvalid()).isFalse();
		assertThat(chain.getRequest()).isNotNull();
		verifyNoInteractions(users);
	}

	@Test
	void 로그인_정보가_AuthenticatedUser가_아닌_세션은_끊는다(CapturedOutput output) throws Exception {
		var session = new MockHttpSession();
		session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
				new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated("someone", null, List.of())));

		run(session);

		assertThat(session.isInvalid()).isTrue();
		assertThat(output).contains("누구의 것인지 알 수 없는 세션을 끊는다: 로그인 정보의 타입=String");
		verifyNoInteractions(users);
	}

	// DB에서 읽은 계정처럼 ID가 채워진 직원 계정. 저장소가 이 계정을 돌려준다.
	private User account() {
		User user = User.create("desk", "{noop}not-a-real-hash", "김직원", UserRole.STAFF, 1L, null);
		ReflectionTestUtils.setField(user, "id", USER_ID);
		when(users.findById(USER_ID)).thenReturn(Optional.of(user));
		return user;
	}

	// 그 계정으로 지금 로그인한 세션. authVersion이 null이면 번호를 넣지 않는다.
	private static MockHttpSession sessionOf(User account, Object authVersion) {
		var user = new AuthenticatedUser(account.getId(), account.getLoginId(), account.getName(), account.getRole(),
				account.getCampusId(), account.getPartnerId());
		var session = new MockHttpSession();
		session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
				new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(user, null, List.of())));
		if (authVersion != null) {
			session.setAttribute(SessionAuthVersion.ATTRIBUTE, authVersion);
		}
		return session;
	}

	private MockFilterChain run(MockHttpSession session) throws Exception {
		var request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
		request.setSession(session);
		var chain = new MockFilterChain();
		filter.doFilter(request, new MockHttpServletResponse(), chain);
		return chain;
	}

}
