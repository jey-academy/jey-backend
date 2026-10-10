package com.jey.core.auth;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import com.jey.core.auth.api.UserRole;
import com.jey.core.auth.domain.User;
import com.jey.core.auth.domain.UserRepository;
import com.jey.core.shared.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// 인증 관리자가 던지는 예외 종류별로 무엇을 응답하는지 확인한다. 성공 흐름은 AuthLoginTest가 실제 세션으로 확인한다.
@ExtendWith(OutputCaptureExtension.class)
class LoginServiceTest {

	private static final long USER_ID = 7L;

	private final AuthenticationManager authenticationManager = mock(AuthenticationManager.class);

	private final SessionAuthenticationStrategy sessionStrategy = mock(SessionAuthenticationStrategy.class);

	private final SecurityContextRepository contextRepository = mock(SecurityContextRepository.class);

	private final UserRepository users = mock(UserRepository.class);

	private final LoginService loginService = new LoginService(authenticationManager, sessionStrategy,
			contextRepository, users, new AuthProperties(List.of(), false, null, false, null, null, null),
			Clock.systemUTC());

	@Test
	void 비밀번호가_틀리면_자격_증명_오류다() {
		when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("bad"));

		assertThatThrownBy(this::login)
				.isInstanceOfSatisfying(BusinessException.class,
						ex -> assertThat(ex.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_CREDENTIALS));
	}

	@Test
	void 계정_상태_때문에_막힌_경우도_자격_증명_오류다() {
		when(authenticationManager.authenticate(any())).thenThrow(new LockedException("locked"));

		assertThatThrownBy(this::login)
				.isInstanceOfSatisfying(BusinessException.class,
						ex -> assertThat(ex.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_CREDENTIALS));
	}

	// DB가 내려가서 계정을 조회하지 못한 경우다. "비밀번호가 틀렸다"로 응답하면 장애가 로그인 실패로 보이고 원인도 남지 않는다.
	@Test
	void 계정_조회에_실패하면_자격_증명_오류로_바꾸지_않는다() {
		var dbDown = new DataAccessResourceFailureException("DB down");
		when(authenticationManager.authenticate(any()))
				.thenThrow(new InternalAuthenticationServiceException("lookup failed", dbDown));

		assertThatThrownBy(this::login)
				.isInstanceOf(IllegalStateException.class)
				.isNotInstanceOf(BusinessException.class)
				.hasRootCause(dbDown);
		verifyNoInteractions(sessionStrategy, contextRepository);
	}

	@Test
	void 그_밖의_인증_내부_오류도_자격_증명_오류로_바꾸지_않는다() {
		when(authenticationManager.authenticate(any())).thenThrow(new AuthenticationServiceException("misconfigured"));

		assertThatThrownBy(this::login).isInstanceOf(IllegalStateException.class);
	}

	// 비밀번호를 검증하는 동안(해시 계산) 계정이 차단됐을 수 있다. 검증 전에 읽어 둔 상태를 믿고 세션을 만들면,
	// 차단 쪽이 세션을 다 끊은 뒤에 이 로그인의 세션이 저장되어 살아남는다. 그래서 세션을 만들기 직전에 DB에서 다시 읽는다.
	@Test
	void 비밀번호_검증_중에_차단된_계정은_로그인되지_않는다(CapturedOutput output) {
		User loadedAsActive = savedUser();
		authenticationSucceedsWith(loadedAsActive);
		User nowDisabled = savedUser();
		nowDisabled.disable();
		when(users.findById(USER_ID)).thenReturn(Optional.of(nowDisabled));

		assertThatThrownBy(this::login)
				.isInstanceOfSatisfying(BusinessException.class,
						ex -> assertThat(ex.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_CREDENTIALS));
		verifyNoInteractions(sessionStrategy, contextRepository);
		assertThat(output).contains("로그인 실패: 비활성 계정 userId=" + USER_ID);
	}

	@Test
	void 비밀번호_검증_중에_사라진_계정은_로그인되지_않는다() {
		authenticationSucceedsWith(savedUser());
		when(users.findById(USER_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(this::login).isInstanceOf(BusinessException.class);
		verifyNoInteractions(sessionStrategy, contextRepository);
	}

	@Test
	void 실패_로그에는_예외_종류만_남고_입력값은_남지_않는다(CapturedOutput output) {
		when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("bad"));

		assertThatThrownBy(this::login).isInstanceOf(BusinessException.class);

		assertThat(output).contains("로그인 실패: BadCredentialsException")
				.doesNotContain("typed-id").doesNotContain("typed-password");
	}

	private void authenticationSucceedsWith(User user) {
		var details = new AccountUserDetails(user);
		when(authenticationManager.authenticate(any()))
				.thenReturn(UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities()));
	}

	// DB에서 읽은 계정처럼 ID가 채워진 계정을 만든다.
	private static User savedUser() {
		User user = User.create("desk", "{noop}not-a-real-hash", "김직원", UserRole.STAFF, 1L);
		ReflectionTestUtils.setField(user, "id", USER_ID);
		return user;
	}

	private void login() {
		loginService.login("typed-id", "typed-password", new MockHttpServletRequest(), new MockHttpServletResponse());
	}

}
