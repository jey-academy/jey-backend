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
 * <p>붙일 수 있는 메서드는 JSON 본문({@code @RequestBody})을 받거나 본문이 없고, 그 자리에서 응답하는 메서드다.
 * 폼이나 파일 올리기를 받는 메서드, 비동기로 응답하는 메서드({@code Callable}, {@code DeferredResult} 등)에는 붙이지 않는다.
 * 다시 돌려주는 것은 상태와 본문뿐이다. 응답 헤더({@code Location} 등)에 뜻을 싣는 메서드라면 헤더도 보관하게 먼저 고친다.
 * 처음의 응답은 접근 규칙(역할)을 다시 묻지 않고 그 계정에 돌려준다.
 *
 * <p>모든 POST 메서드에는 이것이나 {@link NotIdempotent}가 있어야 한다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

}
