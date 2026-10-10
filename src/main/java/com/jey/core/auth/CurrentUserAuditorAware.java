package com.jey.core.auth;

import java.util.Optional;

import com.jey.core.auth.api.AuthenticatedUser;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

// 엔티티의 작성자(created_by)를 채운다. 로그인 없이 일어난 일(첫 관리자 생성, 배치 등)은 비워 둔다.
@Component
class CurrentUserAuditorAware implements AuditorAware<Long> {

	@Override
	public Optional<Long> getCurrentAuditor() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
			return Optional.of(user.id());
		}
		return Optional.empty();
	}

}
