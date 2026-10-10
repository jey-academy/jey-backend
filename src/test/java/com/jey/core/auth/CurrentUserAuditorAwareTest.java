package com.jey.core.auth;

import java.util.List;

import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

class CurrentUserAuditorAwareTest {

	private final CurrentUserAuditorAware auditorAware = new CurrentUserAuditorAware();

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void 로그인한_계정의_ID를_작성자로_준다() {
		var user = new AuthenticatedUser(7L, "staff", "김직원", UserRole.STAFF, 1L);
		SecurityContextHolder.getContext()
				.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));

		assertThat(auditorAware.getCurrentAuditor()).contains(7L);
	}

	@Test
	void 로그인하지_않았으면_작성자가_없다() {
		assertThat(auditorAware.getCurrentAuditor()).isEmpty();
	}

	@Test
	void 익명_사용자는_작성자가_없다() {
		SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("key", "anonymousUser",
				AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

		assertThat(auditorAware.getCurrentAuditor()).isEmpty();
	}

}
