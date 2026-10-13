package com.jey.core.shared;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 이 요청 본문은 모르는 필드가 있으면 거부한다(400). 붙이지 않은 타입은 모르는 필드를 읽지 않고 넘긴다.
 *
 * <p>조건을 받는 본문에 붙인다(ADR-0032). 조건의 이름을 잘못 적으면 그 조건이 조용히 빠져서, 찾으려던 것 대신
 * 조건 없는 전체 목록이 오류 없이 나간다. 틀린 결과를 맞는 결과처럼 보여 주느니 거부한다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RejectUnknownFields {

}
