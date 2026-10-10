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
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

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

	// 로그인한 요청인데 누구인지 알 수 없으면 작성자를 비워 저장하지 않고 멈춘다. 비워 두면 "시스템이 한 일"과 구분되지 않는다.
	@Test
	void 로그인했지만_알_수_없는_사용자면_예외다() {
		SecurityContextHolder.getContext()
				.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("someone", null, List.of()));

		assertThatIllegalStateException().isThrownBy(auditorAware::getCurrentAuditor);
	}

	@Test
	void 익명_사용자는_작성자가_없다() {
		SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("key", "anonymousUser",
				AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

		assertThat(auditorAware.getCurrentAuditor()).isEmpty();
	}

}
