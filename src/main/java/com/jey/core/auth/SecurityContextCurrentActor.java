package com.jey.core.auth;

import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.CurrentActor;
import com.jey.core.shared.Actor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

// 로그인 정보에서 행위자를 읽는 곳은 여기 하나다. 엔티티의 작성자(created_by)도 이 규칙을 쓴다.
@Component
class SecurityContextCurrentActor implements CurrentActor {

	@Override
	public Actor get() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()
				|| authentication instanceof AnonymousAuthenticationToken) {
			return Actor.none();
		}
		if (authentication.getPrincipal() instanceof AuthenticatedUser user) {
			return Actor.user(user.id());
		}
		// 로그인한 요청인데 누구인지 알 수 없는 경우다. 행위자를 비워 두면 "시스템이 한 일"과 구분되지 않으므로 멈춘다.
		Object principal = authentication.getPrincipal();
		throw new IllegalStateException(
				"행위자를 알 수 없는 로그인 정보: " + ((principal != null) ? principal.getClass().getName() : null));
	}

}
