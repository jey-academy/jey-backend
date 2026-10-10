package com.jey.core.auth;

import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.shared.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
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
@Service
public class LoginService {

	private static final Logger log = LoggerFactory.getLogger(LoginService.class);

	private final AuthenticationManager authenticationManager;

	private final SessionAuthenticationStrategy sessionAuthenticationStrategy;

	private final SecurityContextRepository securityContextRepository;

	private final AuthProperties properties;

	private final SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();

	LoginService(AuthenticationManager authenticationManager,
			SessionAuthenticationStrategy sessionAuthenticationStrategy,
			SecurityContextRepository securityContextRepository, AuthProperties properties) {
		this.authenticationManager = authenticationManager;
		this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
		this.securityContextRepository = securityContextRepository;
		this.properties = properties;
	}

	/**
	 * @throws BusinessException 아이디가 없거나, 비밀번호가 틀렸거나, 비활성 계정일 때. 셋을 구분하지 않는다.
	 */
	public AuthenticatedUser login(String loginId, String password, HttpServletRequest request,
			HttpServletResponse response) {
		AccountUserDetails account = authenticate(loginId, password);
		AuthenticatedUser user = account.toAuthenticatedUser();
		// 세션에는 해시를 가진 AccountUserDetails가 아니라 AuthenticatedUser만 저장한다.
		Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(user, null,
				account.getAuthorities());

		// 기존 세션이 있으면 세션 ID를 바꾼 뒤에 로그인 상태를 저장한다(세션 고정 공격 방지).
		sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(authentication);
		SecurityContextHolder.setContext(context);
		securityContextRepository.saveContext(context, request, response);
		request.getSession().setMaxInactiveInterval((int) properties.sessionTimeoutFor(user.role()).toSeconds());
		return user;
	}

	public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
		logoutHandler.logout(request, response, authentication);
	}

	private AccountUserDetails authenticate(String loginId, String password) {
		try {
			Authentication authenticated = authenticationManager
					.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(loginId, password));
			return (AccountUserDetails) authenticated.getPrincipal();
		}
		catch (AuthenticationException ex) {
			// 응답에는 이유를 구분하지 않고, 원인은 여기에만 남긴다.
			// 입력한 아이디는 남기지 않는다. 아이디 칸에 비밀번호를 잘못 넣는 일이 흔하다.
			log.info("로그인 실패: {}", ex.getClass().getSimpleName());
			throw new BusinessException(AuthErrorCode.INVALID_CREDENTIALS, null, ex);
		}
	}

}
