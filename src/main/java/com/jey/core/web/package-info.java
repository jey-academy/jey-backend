/**
 * 웹 계층 공통 처리. 예외를 RFC 9457 Problem Details로 바꾼다.
 * 이 패키지의 타입은 다른 모듈에 공개하지 않지만, 예외 변환은 모든 모듈의 컨트롤러에 적용된다.
 * 모듈이 쓰는 규약 타입은 {@code core.shared}에 있다.
 */
package com.jey.core.web;
