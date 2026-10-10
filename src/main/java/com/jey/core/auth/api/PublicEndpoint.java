package com.jey.core.auth.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 로그인 없이 부를 수 있는 API(로그인, 학부모 패스 열람 등).
 *
 * <p>이것만으로는 열리지 않는다. 보안 설정의 허용 경로에도 있어야 한다. 한쪽에만 있으면 닫힌 쪽으로 동작한다(401).
 */
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PublicEndpoint {

}
