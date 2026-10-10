package com.jey.core.auth.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 적힌 역할만 부를 수 있는 API. 컨트롤러 메서드나 클래스에 붙인다. 메서드에 붙인 것이 클래스에 붙인 것보다 우선한다.
 *
 * <p>관리자도 적혀 있어야 통과한다. 관리자가 쓰는 기능에는 {@code ADMIN}을 같이 적는다.
 * 컨트롤러 메서드에는 이것, {@link AnyRole}, {@link PublicEndpoint} 중 하나가 반드시 있어야 하고, 없으면 서버가 뜨지 않는다.
 */
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AllowedRoles {

	UserRole[] value();

}
