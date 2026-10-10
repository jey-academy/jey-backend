package com.jey.core.auth.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 로그인한 누구나 부를 수 있는 API(내 정보, 로그아웃 등). 연계 학원 계정도 포함된다.
 * 역할을 가려야 하는 기능에는 {@link AllowedRoles}를 쓴다.
 */
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AnyRole {

}
