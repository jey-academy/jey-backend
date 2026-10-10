package com.jey.core.auth;

import java.util.List;
import java.util.Set;

import com.jey.core.auth.api.AllowedRoles;
import com.jey.core.auth.api.AnyRole;
import com.jey.core.auth.api.PublicEndpoint;
import com.jey.core.auth.api.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.web.method.HandlerMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class AccessRuleTest {

	@Test
	void 클래스에_붙인_표시가_메서드에_적용된다() throws Exception {
		assertThat(AccessRule.of(handler(new DeskController(), "inherited")))
				.isEqualTo(new AccessRule.Roles(Set.of(UserRole.ADMIN, UserRole.STAFF)));
	}

	// 좁히는 것도, 넓히는 것도 메서드에 적은 대로다.
	@Test
	void 메서드에_붙인_표시가_클래스의_표시보다_우선한다() throws Exception {
		assertThat(AccessRule.of(handler(new DeskController(), "adminOnly")))
				.isEqualTo(new AccessRule.Roles(Set.of(UserRole.ADMIN)));
		assertThat(AccessRule.of(handler(new DeskController(), "anyone"))).isEqualTo(new AccessRule.AnyLoggedIn());
		assertThat(AccessRule.of(handler(new DeskController(), "open"))).isEqualTo(new AccessRule.NoLogin());
	}

	@Test
	void 표시가_없으면_규칙을_만들지_못한다() throws Exception {
		assertThatIllegalStateException().isThrownBy(() -> AccessRule.of(handler(new UnmarkedController(), "forgotten")))
				.withMessageContaining("접근 규칙 표시가 없다")
				.withMessageContaining("UnmarkedController#forgotten");
	}

	@Test
	void 한_곳에_표시를_둘_붙이면_규칙을_만들지_못한다() throws Exception {
		assertThatIllegalStateException().isThrownBy(() -> AccessRule.of(handler(new UnmarkedController(), "both")))
				.withMessageContaining("둘 이상");
	}

	// 역할이 하나도 없으면 아무도 못 부르는 API다. 실수이므로 막는다.
	@Test
	void 역할을_하나도_적지_않으면_규칙을_만들지_못한다() throws Exception {
		assertThatIllegalStateException().isThrownBy(() -> AccessRule.of(handler(new UnmarkedController(), "nobody")))
				.withMessageContaining("역할이 없다");
	}

	@Test
	void 적힌_역할의_권한이_있어야_통과한다() {
		var rule = new AccessRule.Roles(Set.of(UserRole.ADMIN, UserRole.STAFF));

		assertThat(rule.allows(loggedInWith("ROLE_STAFF"))).isTrue();
		assertThat(rule.allows(loggedInWith("ROLE_PARTNER"))).isFalse();
		// 앞에 ROLE_이 없는 이름이나 다른 권한은 역할로 치지 않는다.
		assertThat(rule.allows(loggedInWith("STAFF", "ROLE_USER"))).isFalse();
	}

	// Swagger 문서나 오류 처리처럼 라이브러리가 등록한 컨트롤러는 우리 표시를 붙일 수 없다.
	@Test
	void 우리_패키지_밖의_컨트롤러는_검사하지_않는다() throws Exception {
		var foreign = new HandlerMethod(new Object(), Object.class.getMethod("toString"));

		assertThat(AccessRule.appliesTo(foreign)).isFalse();
		assertThat(AccessRule.appliesTo(handler(new DeskController(), "inherited"))).isTrue();
		assertThat(AccessRule.requireAllMarked(List.of(foreign))).isZero();
	}

	@Test
	void 표시가_잘못된_API가_있으면_전부_모아서_알린다() throws Exception {
		var handlers = List.of(handler(new DeskController(), "inherited"), handler(new UnmarkedController(), "forgotten"),
				handler(new UnmarkedController(), "both"));

		assertThatIllegalStateException().isThrownBy(() -> AccessRule.requireAllMarked(handlers))
				.withMessageContaining("UnmarkedController#forgotten")
				.withMessageContaining("UnmarkedController#both")
				.withMessageNotContaining("DeskController");
	}

	// 시작할 때 이 수가 0이면 검사가 매핑을 보지 못한 것이다.
	@Test
	void 확인한_컨트롤러_메서드의_수를_돌려준다() throws Exception {
		var foreign = new HandlerMethod(new Object(), Object.class.getMethod("toString"));
		var handlers = List.of(handler(new DeskController(), "inherited"), handler(new DeskController(), "adminOnly"),
				foreign);

		assertThat(AccessRule.requireAllMarked(handlers)).isEqualTo(2);
	}

	private static HandlerMethod handler(Object controller, String methodName) throws NoSuchMethodException {
		return new HandlerMethod(controller, controller.getClass().getDeclaredMethod(methodName));
	}

	private static UsernamePasswordAuthenticationToken loggedInWith(String... authorities) {
		return UsernamePasswordAuthenticationToken.authenticated("someone", null,
				AuthorityUtils.createAuthorityList(authorities));
	}

	@AllowedRoles({ UserRole.ADMIN, UserRole.STAFF })
	static class DeskController {

		void inherited() {
		}

		@AllowedRoles(UserRole.ADMIN)
		void adminOnly() {
		}

		@AnyRole
		void anyone() {
		}

		@PublicEndpoint
		void open() {
		}

	}

	static class UnmarkedController {

		void forgotten() {
		}

		@AnyRole
		@PublicEndpoint
		void both() {
		}

		@AllowedRoles({})
		void nobody() {
		}

	}

}
