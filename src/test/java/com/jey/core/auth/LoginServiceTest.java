package com.jey.core.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.jey.core.auth.LoginAttemptLimiter.Blocked;
import com.jey.core.auth.LoginAttemptLimiter.Scope;
import com.jey.core.auth.api.UserRole;
import com.jey.core.auth.domain.User;
import com.jey.core.auth.domain.UserRepository;
import com.jey.core.shared.BusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpHeaders;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

	// 따로 정하지 않으면 "한도를 넘지 않았다"(빈 값)로 답한다.
	private final LoginAttemptLimiter attemptLimiter = mock(LoginAttemptLimiter.class);

	private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

	private final LoginService loginService = new LoginService(authenticationManager, sessionStrategy,
			contextRepository, users, attemptLimiter, new AuthProperties(List.of(), false, null, false, null, null, null),
			meterRegistry, Clock.systemUTC());

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

	// 한도를 넘은 뒤에는 비밀번호가 맞는지조차 알려 주지 않는다. 해시 계산도 하지 않는다.
	@Test
	void 시도_한도를_넘으면_비밀번호를_검증하지_않고_거부한다(CapturedOutput output) {
		when(attemptLimiter.countAttempt("typed-id", "127.0.0.1"))
				.thenReturn(Optional.of(new Blocked(Scope.IP, Duration.ofMillis(89_001))));
		var response = new MockHttpServletResponse();

		assertThatThrownBy(() -> loginService.login("typed-id", "typed-password", new MockHttpServletRequest(),
				response))
				.isInstanceOfSatisfying(BusinessException.class,
						ex -> assertThat(ex.getErrorCode()).isEqualTo(AuthErrorCode.TOO_MANY_ATTEMPTS));

		verifyNoInteractions(authenticationManager, sessionStrategy, contextRepository);
		// 89.001초는 90초로 올린다. 내림하면 프론트가 아직 막혀 있을 때 다시 시도한다.
		assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("90");
		// 어느 한도에 걸렸는지 남아야 "한 계정이 막혔다"와 "그 IP 전체가 막혔다"를 구분할 수 있다.
		assertThat(output).contains("로그인 시도 제한 초과: ip=127.0.0.1 scope=IP retryAfter=90s")
				.doesNotContain("typed-id");
	}

	@Test
	void 남은_시간이_1초보다_짧아도_Retry_After는_1초다() {
		when(attemptLimiter.countAttempt(any(), any()))
				.thenReturn(Optional.of(new Blocked(Scope.LOGIN_ID_AND_IP, Duration.ofMillis(1))));
		var response = new MockHttpServletResponse();

		assertThatThrownBy(() -> loginService.login("typed-id", "typed-password", new MockHttpServletRequest(),
				response)).isInstanceOf(BusinessException.class);

		assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
	}

	// Redis가 응답하지 않아 횟수를 세지 못한 경우다. "셀 수 없으니 통과"로 바꾸면 장애 동안 제한이 꺼진다.
	@Test
	void 시도_횟수를_세지_못하면_비밀번호를_검증하지_않는다() {
		var redisDown = new RedisConnectionFailureException("down");
		when(attemptLimiter.countAttempt(any(), any())).thenThrow(redisDown);

		assertThatThrownBy(this::login).isSameAs(redisDown);

		verifyNoInteractions(authenticationManager, sessionStrategy, contextRepository);
	}

	// 비밀번호가 맞았어도 횟수를 되돌리지 못했으면 로그인시키지 않는다. 세션도 같은 Redis에 있어 어차피 저장할 수 없다.
	@Test
	void 성공_처리에_실패하면_세션을_만들지_않는다() {
		User user = savedUser();
		authenticationSucceedsWith(user);
		when(users.findById(USER_ID)).thenReturn(Optional.of(user));
		var redisDown = new RedisConnectionFailureException("down");
		doThrow(redisDown).when(attemptLimiter).loginSucceeded(any(), any());

		assertThatThrownBy(this::login).isSameAs(redisDown);

		verifyNoInteractions(sessionStrategy, contextRepository);
	}

	@Test
	void 로그인에_성공하면_시도_횟수를_지운다() {
		User user = savedUser();
		authenticationSucceedsWith(user);
		when(users.findById(USER_ID)).thenReturn(Optional.of(user));

		login();

		verify(attemptLimiter).countAttempt("typed-id", "127.0.0.1");
		verify(attemptLimiter).loginSucceeded("typed-id", "127.0.0.1");
		assertThat(attempts("success", "none")).isEqualTo(1);
	}

	// 실패와 차단이 갑자기 늘면 누군가 비밀번호를 대입하고 있다는 신호다. 로그를 뒤지지 않고도 보이게 지표로 센다.
	@Test
	void 로그인_시도를_결과별로_센다() {
		when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("bad"));
		assertThatThrownBy(this::login).isInstanceOf(BusinessException.class);
		assertThatThrownBy(this::login).isInstanceOf(BusinessException.class);

		when(attemptLimiter.countAttempt(any(), any()))
				.thenReturn(Optional.of(new Blocked(Scope.LOGIN_ID_AND_IP, Duration.ofMinutes(1))));
		assertThatThrownBy(this::login).isInstanceOf(BusinessException.class);

		assertThat(attempts("failure", "none")).isEqualTo(2);
		assertThat(attempts("blocked", "login_id_and_ip")).isEqualTo(1);
		assertThat(meterRegistry.find(LoginService.ATTEMPTS_METRIC).tag("result", "success").counter()).isNull();
	}

	@Test
	void 비활성_계정의_로그인도_실패로_센다() {
		authenticationSucceedsWith(savedUser());
		User nowDisabled = savedUser();
		nowDisabled.disable();
		when(users.findById(USER_ID)).thenReturn(Optional.of(nowDisabled));

		assertThatThrownBy(this::login).isInstanceOf(BusinessException.class);

		assertThat(attempts("failure", "none")).isEqualTo(1);
	}

	// 내부 오류는 로그인 실패가 아니다. 실패로 세면 DB 장애가 공격처럼 보인다.
	@Test
	void 내부_오류는_로그인_실패로_세지_않는다() {
		when(authenticationManager.authenticate(any())).thenThrow(new AuthenticationServiceException("misconfigured"));

		assertThatThrownBy(this::login).isInstanceOf(IllegalStateException.class);

		assertThat(meterRegistry.find(LoginService.ATTEMPTS_METRIC).counters()).isEmpty();
	}

	private double attempts(String result, String scope) {
		return meterRegistry.get(LoginService.ATTEMPTS_METRIC).tag("result", result).tag("scope", scope).counter()
				.count();
	}

	@Test
	void 비밀번호가_틀리면_시도_횟수가_남는다() {
		when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("bad"));

		assertThatThrownBy(this::login).isInstanceOf(BusinessException.class);

		verify(attemptLimiter).countAttempt("typed-id", "127.0.0.1");
		verify(attemptLimiter, never()).loginSucceeded(any(), any());
	}

	// 비밀번호가 맞아도 비활성 계정이면 실패다. 횟수를 지우면 차단된 계정의 비밀번호를 한도 없이 확인할 수 있다.
	@Test
	void 비활성_계정은_비밀번호가_맞아도_시도_횟수가_남는다() {
		authenticationSucceedsWith(savedUser());
		User nowDisabled = savedUser();
		nowDisabled.disable();
		when(users.findById(USER_ID)).thenReturn(Optional.of(nowDisabled));

		assertThatThrownBy(this::login).isInstanceOf(BusinessException.class);

		verify(attemptLimiter, never()).loginSucceeded(any(), any());
	}

	private void authenticationSucceedsWith(User user) {
		var details = new AccountUserDetails(user);
		when(authenticationManager.authenticate(any()))
				.thenReturn(UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities()));
	}

	// DB에서 읽은 계정처럼 ID가 채워진 계정을 만든다.
	private static User savedUser() {
		User user = User.create("desk", "{noop}not-a-real-hash", "김직원", UserRole.STAFF, 1L, null);
		ReflectionTestUtils.setField(user, "id", USER_ID);
		return user;
	}

	private void login() {
		loginService.login("typed-id", "typed-password", new MockHttpServletRequest(), new MockHttpServletResponse());
	}

}
