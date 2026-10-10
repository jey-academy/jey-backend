package com.jey.core.auth;

import java.lang.reflect.AnnotatedElement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import com.jey.core.auth.api.AllowedRoles;
import com.jey.core.auth.api.AnyRole;
import com.jey.core.auth.api.PublicEndpoint;
import com.jey.core.auth.api.UserRole;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.util.ClassUtils;
import org.springframework.web.method.HandlerMethod;

/**
 * 컨트롤러 메서드 하나를 누가 부를 수 있는가. 메서드나 클래스에 붙인 표시({@link AllowedRoles}, {@link AnyRole},
 * {@link PublicEndpoint})에서 읽는다.
 */
sealed interface AccessRule {

	String OUR_PACKAGE = "com.jey";

	/** 로그인 없이. */
	record NoLogin() implements AccessRule {
	}

	/** 로그인한 누구나. */
	record AnyLoggedIn() implements AccessRule {
	}

	/** 적힌 역할만. */
	record Roles(Set<UserRole> roles) implements AccessRule {

		boolean allows(Authentication authentication) {
			return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
					.anyMatch(authority -> roles.stream().anyMatch(role -> role.authority().equals(authority)));
		}

	}

	/** 우리가 만든 컨트롤러인지. 라이브러리가 등록한 컨트롤러(API 문서 등)에는 표시를 붙일 수 없으므로 검사하지 않는다. */
	static boolean appliesTo(HandlerMethod handler) {
		String packageName = ClassUtils.getUserClass(handler.getBeanType()).getPackageName();
		return packageName.equals(OUR_PACKAGE) || packageName.startsWith(OUR_PACKAGE + ".");
	}

	/**
	 * 메서드에 붙인 표시가 클래스에 붙인 표시보다 우선한다.
	 *
	 * @throws IllegalStateException 표시가 없거나, 한 곳에 둘 이상이거나, 역할을 하나도 적지 않았을 때
	 */
	static AccessRule of(HandlerMethod handler) {
		List<AccessRule> onMethod = declaredOn(handler.getMethod());
		List<AccessRule> declared = onMethod.isEmpty() ? declaredOn(ClassUtils.getUserClass(handler.getBeanType()))
				: onMethod;
		if (declared.isEmpty()) {
			throw new IllegalStateException("접근 규칙 표시가 없다: " + describe(handler));
		}
		if (declared.size() > 1) {
			throw new IllegalStateException("접근 규칙 표시가 둘 이상이다: " + describe(handler));
		}
		if (declared.get(0) instanceof Roles roles && roles.roles().isEmpty()) {
			throw new IllegalStateException("@AllowedRoles에 역할이 없다: " + describe(handler));
		}
		return declared.get(0);
	}

	/**
	 * 우리 컨트롤러 메서드 전부에 올바른 표시가 있는지 확인한다. 서버를 시작할 때 부른다.
	 * 표시를 빠뜨린 API가 "로그인한 누구나"로 열린 채 나가는 것을 막는다.
	 *
	 * @throws IllegalStateException 잘못된 것이 하나라도 있을 때. 메시지에 전부 나열한다.
	 */
	static void requireAllMarked(Collection<HandlerMethod> handlers) {
		List<String> problems = new ArrayList<>();
		for (HandlerMethod handler : handlers) {
			if (!appliesTo(handler)) {
				continue;
			}
			try {
				of(handler);
			}
			catch (IllegalStateException ex) {
				problems.add(ex.getMessage());
			}
		}
		if (!problems.isEmpty()) {
			throw new IllegalStateException("접근 규칙이 잘못된 API가 있어 서버를 시작하지 않는다. 컨트롤러 메서드나 클래스에 "
					+ "@AllowedRoles, @AnyRole, @PublicEndpoint 중 하나만 붙인다. " + problems);
		}
	}

	private static List<AccessRule> declaredOn(AnnotatedElement element) {
		List<AccessRule> rules = new ArrayList<>();
		if (AnnotatedElementUtils.hasAnnotation(element, PublicEndpoint.class)) {
			rules.add(new NoLogin());
		}
		if (AnnotatedElementUtils.hasAnnotation(element, AnyRole.class)) {
			rules.add(new AnyLoggedIn());
		}
		AllowedRoles allowed = AnnotatedElementUtils.findMergedAnnotation(element, AllowedRoles.class);
		if (allowed != null) {
			rules.add(new Roles(Set.copyOf(Arrays.asList(allowed.value()))));
		}
		return rules;
	}

	private static String describe(HandlerMethod handler) {
		return ClassUtils.getUserClass(handler.getBeanType()).getSimpleName() + "#" + handler.getMethod().getName();
	}

}
