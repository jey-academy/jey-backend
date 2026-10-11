package com.jey.core.shared;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 이 POST는 {@code Idempotency-Key}를 받지 않는다(ADR-0027). 다시 보낸 요청은 한 번 더 실행된다.
 *
 * <p>빠뜨린 것과 일부러 받지 않는 것을 가르려고 둔다. 왜 받지 않아도 되는지를 적는다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface NotIdempotent {

	/** 다시 실행돼도 되는 이유 */
	String value();

}
