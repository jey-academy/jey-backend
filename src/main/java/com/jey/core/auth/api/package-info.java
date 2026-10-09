/**
 * 로그인, 역할, 권한. 다른 모듈에 공개하는 영역(Facade, DTO, 이벤트).
 * 엔티티와 Repository는 이 패키지 밖(모듈 내부)에 둔다.
 */
@org.springframework.modulith.NamedInterface("auth")
package com.jey.core.auth.api;
