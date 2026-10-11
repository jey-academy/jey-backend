package com.jey.core.shared;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 이 POST는 {@code Idempotency-Key} 헤더를 받는다(ADR-0027). 컨트롤러 메서드에 붙인다.
 *
 * <p>같은 계정이 같은 키로 같은 요청을 다시 보내면 메서드를 다시 실행하지 않고 처음의 응답을 돌려준다.
 * 키가 없으면 400이다. 로그인이 필요한 경로에만 붙인다(키를 계정별로 본다).
 *
 * <p>모든 POST 메서드에는 이것이나 {@link NotIdempotent}가 있어야 한다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

}
