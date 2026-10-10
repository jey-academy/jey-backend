package com.jey.core.auth;

import java.time.Clock;
import java.util.List;

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
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// 인증 관리자가 던지는 예외 종류별로 무엇을 응답하는지 확인한다. 성공 흐름은 AuthLoginTest가 실제 세션으로 확인한다.
@ExtendWith(OutputCaptureExtension.class)
class LoginServiceTest {

	private final AuthenticationManager authenticationManager = mock(AuthenticationManager.class);

	private final SessionAuthenticationStrategy sessionStrategy = mock(SessionAuthenticationStrategy.class);

	private final SecurityContextRepository contextRepository = mock(SecurityContextRepository.class);

	private final LoginService loginService = new LoginService(authenticationManager, sessionStrategy,
			contextRepository, new AuthProperties(List.of(), null, false, null, null, null), Clock.systemUTC());

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

	@Test
	void 실패_로그에는_예외_종류만_남고_입력값은_남지_않는다(CapturedOutput output) {
		when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("bad"));

		assertThatThrownBy(this::login).isInstanceOf(BusinessException.class);

		assertThat(output).contains("로그인 실패: BadCredentialsException")
				.doesNotContain("typed-id").doesNotContain("typed-password");
	}

	private void login() {
		loginService.login("typed-id", "typed-password", new MockHttpServletRequest(), new MockHttpServletResponse());
	}

}
