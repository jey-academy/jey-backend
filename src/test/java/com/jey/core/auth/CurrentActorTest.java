package com.jey.core.auth;

import java.util.List;

import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.CurrentActor;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.Actor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

// 감사 로그의 행위자, 이벤트의 행위자, 엔티티의 작성자가 모두 이 규칙으로 정해진다.
class CurrentActorTest {

	private final CurrentActor currentActor = new SecurityContextCurrentActor();

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void 로그인한_요청이면_그_계정이다() {
		login(UsernamePasswordAuthenticationToken.authenticated(
				new AuthenticatedUser(42L, "staff", "직원", UserRole.STAFF, 1L, null), null,
				List.of(new SimpleGrantedAuthority(UserRole.STAFF.authority()))));

		assertThat(currentActor.get()).isEqualTo(Actor.user(42));
		assertThat(currentActor.get().idOrNull()).isEqualTo(42L);
	}

	// 배치, 이벤트 리스너, 로그인하지 않은 요청.
	@Test
	void 로그인이_없으면_행위자가_없다() {
		assertThat(currentActor.get()).isEqualTo(Actor.none());
		assertThat(currentActor.get().idOrNull()).isNull();

		login(new AnonymousAuthenticationToken("key", "anonymousUser",
				List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
		assertThat(currentActor.get()).isEqualTo(Actor.none());

		login(UsernamePasswordAuthenticationToken.unauthenticated("staff", "password"));
		assertThat(currentActor.get()).isEqualTo(Actor.none());
	}

	// 로그인은 했는데 누구인지 모르는 채로 넘어가면, 그 사람이 한 일이 "시스템이 한 일"로 남는다.
	@Test
	void 로그인했는데_누구인지_알_수_없으면_멈춘다() {
		login(UsernamePasswordAuthenticationToken.authenticated("someone", null,
				List.of(new SimpleGrantedAuthority("ROLE_STAFF"))));

		assertThatIllegalStateException().isThrownBy(currentActor::get).withMessageNotContaining("someone");
	}

	@Test
	void 계정_ID는_양수여야_한다() {
		org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(() -> Actor.user(0));
	}

	private static void login(Authentication authentication) {
		SecurityContextHolder.getContext().setAuthentication(authentication);
	}

}
