package com.jey.core.auth;

import java.time.Clock;

import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.domain.User;
import com.jey.core.auth.domain.UserRepository;
import com.jey.core.shared.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AccountStatusException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;

/**
 * 아이디·비밀번호 로그인과 로그아웃. 인증 상태는 세션(Redis)에 저장한다.
 */
// web 패키지의 AuthController가 쓰므로 public이다. 생성자는 패키지 전용이라 스프링만 만든다.
@Service
public class LoginService {

	private static final Logger log = LoggerFactory.getLogger(LoginService.class);

	private final AuthenticationManager authenticationManager;

	private final SessionAuthenticationStrategy sessionAuthenticationStrategy;

	private final SecurityContextRepository securityContextRepository;

	private final UserRepository users;

	private final AuthProperties properties;

	private final Clock clock;

	private final SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();

	LoginService(AuthenticationManager authenticationManager,
			SessionAuthenticationStrategy sessionAuthenticationStrategy,
			SecurityContextRepository securityContextRepository, UserRepository users, AuthProperties properties,
			Clock clock) {
		this.authenticationManager = authenticationManager;
		this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
		this.securityContextRepository = securityContextRepository;
		this.users = users;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * @throws BusinessException 아이디가 없거나, 비밀번호가 틀렸거나, 비활성 계정일 때. 응답에서는 셋을 구분하지 않는다.
	 * @throws IllegalStateException 계정을 조회하지 못하는 등 내부 문제로 로그인을 처리하지 못했을 때
	 */
	public AuthenticatedUser login(String loginId, String password, HttpServletRequest request,
			HttpServletResponse response) {
		AccountUserDetails account = authenticate(loginId, password);
		AuthenticatedUser user = account.toAuthenticatedUser();
		requireStillActive(user);
		// 인증 관리자가 돌려준 토큰의 principal은 AccountUserDetails다. 세션에는 그 대신 AuthenticatedUser만 저장한다.
		Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(user, null,
				account.getAuthorities());

		// 기존 세션이 있으면 세션 ID를 바꾼 뒤에 로그인 상태를 저장한다(세션 고정 공격 방지).
		sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
		HttpSession session = request.getSession();
		// 만료 시간과 최대 유지 시각을 로그인 정보보다 먼저 넣는다. 로그인 정보만 있고 상한이 없는 세션이 생기지 않게 한다.
		// 만료 시간은 마지막 요청 기준이라 쓰는 동안 계속 연장된다.
		session.setMaxInactiveInterval((int) properties.sessionTimeoutFor(user.role()).toSeconds());
		// 최대 유지 시각은 로그인 시점 기준이다. 계속 쓰고 있어도 이 시각이 지나면 SessionMaxLifetimeFilter가 끊는다.
		session.setAttribute(SessionMaxLifetimeFilter.EXPIRES_AT_ATTRIBUTE,
				clock.instant().plus(properties.sessionMaxLifetimeFor(user.role())).toEpochMilli());
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(authentication);
		SecurityContextHolder.setContext(context);
		securityContextRepository.saveContext(context, request, response);
		log.info("로그인: userId={} role={}", user.id(), user.role());
		return user;
	}

	public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
		logoutHandler.logout(request, response, authentication);
		log.info("로그아웃: userId={}", (authentication != null) ? authentication.getName() : null);
	}

	private AccountUserDetails authenticate(String loginId, String password) {
		try {
			Authentication authenticated = authenticationManager
					.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(loginId, password));
			return (AccountUserDetails) authenticated.getPrincipal();
		}
		catch (BadCredentialsException | AccountStatusException ex) {
			// 응답에는 이유를 구분하지 않는다. 로그에는 예외 종류만 남기며, 없는 아이디와 틀린 비밀번호는 둘 다
			// BadCredentialsException으로 보인다. 입력한 아이디는 남기지 않는다. 아이디 칸에 비밀번호를 잘못 넣는 일이 흔하다.
			log.info("로그인 실패: {}", ex.getClass().getSimpleName());
			throw new BusinessException(AuthErrorCode.INVALID_CREDENTIALS, null, ex);
		}
		catch (AuthenticationException ex) {
			// 계정 조회 중 DB 오류 같은 내부 문제도 AuthenticationException으로 감싸져 온다.
			// 이것을 "비밀번호가 틀렸다"로 응답하면 장애가 로그인 실패로 보이고 원인도 남지 않는다.
			// AuthenticationException인 채로 던지면 401로 바뀌므로 다른 예외로 감싸 500과 원인 로그가 남게 한다.
			throw new IllegalStateException("로그인 처리 중 내부 오류", ex);
		}
	}

	// 비활성 여부는 비밀번호가 맞은 뒤에 확인한다. 먼저 확인하면 비활성 계정만 해시 계산 없이 빨리 실패해서,
	// 응답 시간으로 "존재하지만 비활성인 아이디"를 가려낼 수 있다.
	// 비밀번호 검증 전에 읽어 둔 값을 쓰지 않고 지금 DB에서 다시 읽는다. 해시를 계산하는 동안 계정이 차단됐을 수 있고,
	// 그 경우 차단 쪽이 세션을 끊은 뒤에 이 로그인의 세션이 저장되어 살아남기 때문이다.
	// 다시 읽은 직후부터 세션이 저장될 때까지의 짧은 틈은 남는다(ADR-0019).
	private void requireStillActive(AuthenticatedUser user) {
		boolean active = users.findById(user.id()).map(User::isActive).orElse(false);
		if (!active) {
			log.info("로그인 실패: 비활성 계정 userId={}", user.id());
			throw new BusinessException(AuthErrorCode.INVALID_CREDENTIALS);
		}
	}

}
