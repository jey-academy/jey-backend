package com.jey.core.auth;

import java.util.Optional;

import com.jey.core.auth.api.AuthenticatedUser;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

// 엔티티의 작성자(created_by)를 채운다. 로그인 없이 일어난 일(첫 관리자 생성, 배치 등)은 비워 둔다.
@Component
class CurrentUserAuditorAware implements AuditorAware<Long> {

	@Override
	public Optional<Long> getCurrentAuditor() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()
				|| authentication instanceof AnonymousAuthenticationToken) {
			return Optional.empty();
		}
		if (authentication.getPrincipal() instanceof AuthenticatedUser user) {
			return Optional.of(user.id());
		}
		// 로그인한 요청인데 누구인지 알 수 없는 경우다. 작성자를 비워 저장하면 "시스템이 한 일"과 구분되지 않으므로 멈춘다.
		throw new IllegalStateException(
				"작성자를 알 수 없는 로그인 정보: " + authentication.getPrincipal().getClass().getName());
	}

}
