package com.jey;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Locale;

import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithSecurityContext;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

/**
 * 테스트를 역할·지점을 가진 사용자로 로그인한 상태에서 돌린다. 실제 로그인과 같은 모양의 로그인 정보({@link AuthenticatedUser})를 넣는다.
 *
 * <p>{@code @WithMockUser}는 로그인 정보의 타입이 달라서 역할 검사는 통과해도 {@code @AuthenticationPrincipal AuthenticatedUser}를
 * 받는 컨트롤러와 작성자 기록에서는 쓸 수 없다.
 */
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@WithSecurityContext(factory = WithJeyUser.Factory.class)
public @interface WithJeyUser {

	/** 지점이나 연계 학원이 없음. 애너테이션 값에는 null을 쓸 수 없어 0으로 나타낸다. */
	long NONE = 0;

	UserRole role();

	long id() default 1;

	/** 직원은 반드시 준다. */
	long campusId() default NONE;

	/** 연계 학원은 반드시 준다. */
	long partnerId() default NONE;

	final class Factory implements WithSecurityContextFactory<WithJeyUser> {

		@Override
		public SecurityContext createSecurityContext(WithJeyUser annotation) {
			UserRole role = annotation.role();
			var user = new AuthenticatedUser(annotation.id(), "test-" + role.name().toLowerCase(Locale.ROOT),
					"테스트 사용자", role, nullIfNone(annotation.campusId()), nullIfNone(annotation.partnerId()));
			SecurityContext context = SecurityContextHolder.createEmptyContext();
			context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null,
					List.of(new SimpleGrantedAuthority(role.authority()))));
			return context;
		}

		private static Long nullIfNone(long value) {
			return (value == NONE) ? null : value;
		}

	}

}
