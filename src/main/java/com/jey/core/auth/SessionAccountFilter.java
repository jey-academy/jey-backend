package com.jey.core.auth;

import java.io.IOException;
import java.util.Optional;

import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.domain.User;
import com.jey.core.auth.domain.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청마다, 세션에 든 계정이 지금도 그 세션으로 일해도 되는지 확인한다 (ADR-0031).
 * 세션에는 로그인할 때의 역할과 지점이 들어 있다. 계정을 차단하거나 역할·지점·비밀번호를 바꾼 쪽이 세션을 치우지만,
 * 그것은 커밋한 뒤의 별도 단계라 실패할 수 있다. 막는 것은 여기서 한다: 유효하지 않은 세션은 끊는다.
 *
 * <p>무엇이 유효한지는 {@link User#stillValidFor}가 정한다. 세션에 든 권한 판 번호({@link LoginService#AUTH_VERSION_ATTRIBUTE})가
 * 없거나 읽을 수 없는 세션도 끊는다. 번호를 읽지 못했다고 통과시키면 이 확인이 조용히 사라진다.
 *
 * <p>끊은 뒤에도 요청은 계속 진행한다. 세션이 없어졌으므로 인증이 필요한 경로라면 401을 받는다.
 * 세션이 없는 요청은 계정을 읽지 않는다.
 *
 * <p>계정을 읽지 못하면(DB 장애) 예외를 그대로 올린다. 요청은 서버 오류로 끝나고 세션은 남는다.
 * 통과시키면 그동안 차단이 풀리고, 세션을 끊으면 장애가 "로그인이 풀렸다"로 보인다.
 */
final class SessionAccountFilter extends OncePerRequestFilter {

	private static final String SECURITY_CONTEXT_ATTRIBUTE = HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;

	private static final Logger log = LoggerFactory.getLogger(SessionAccountFilter.class);

	private final UserRepository users;

	SessionAccountFilter(UserRepository users) {
		this.users = users;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		HttpSession session = request.getSession(false);
		if (session != null) {
			terminateIfOutdated(session);
		}
		chain.doFilter(request, response);
	}

	private void terminateIfOutdated(HttpSession session) {
		// 로그인 정보가 없는 세션은 앞의 SessionMaxLifetimeFilter가 이미 끊었다. 여기까지 오면 볼 것이 없다.
		if (!(session.getAttribute(SECURITY_CONTEXT_ATTRIBUTE) instanceof SecurityContext context)
				|| context.getAuthentication() == null) {
			return;
		}
		if (!(context.getAuthentication().getPrincipal() instanceof AuthenticatedUser sessionUser)) {
			log.warn("누구의 것인지 알 수 없는 세션을 끊는다");
			session.invalidate();
			return;
		}
		if (!(session.getAttribute(LoginService.AUTH_VERSION_ATTRIBUTE) instanceof Long sessionAuthVersion)) {
			log.warn("권한 판 번호가 없는 세션을 끊는다: userId={}", sessionUser.id());
			session.invalidate();
			return;
		}
		Optional<User> account = users.findById(sessionUser.id());
		if (account.isEmpty()) {
			// 계정은 지우지 않는다. 세션은 있는데 계정이 없다면 자료가 이상한 것이다.
			log.warn("계정이 없는 세션을 끊는다: userId={}", sessionUser.id());
			session.invalidate();
		}
		else if (!account.get().stillValidFor(sessionUser, sessionAuthVersion)) {
			log.info("계정이 차단됐거나 바뀐 뒤에 남은 세션을 끊는다: userId={}", sessionUser.id());
			session.invalidate();
		}
	}

}
