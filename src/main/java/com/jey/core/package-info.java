/**
 * 기초 모듈. 로그인·권한, 지점·반, 학생·학부모, 알림 창구, 파일, 감사 로그, 공용 값 객체.
 * 다른 모듈에 의존하지 않는다. 하위 패키지는 {@code api}만 공개하고, {@code shared}는 통째로 공개한다.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Core", allowedDependencies = {})
package com.jey.core;
