package com.jey.core.auth.api;

import java.io.Serializable;
import java.security.Principal;
import java.util.Objects;

/**
 * 로그인한 사용자. 세션에 저장되므로 비밀번호나 해시를 담지 않는다.
 *
 * <p>Java 직렬화로 Redis에 저장된다. 클래스 이름이나 필드 타입을 바꾸면 기존 세션을 읽지 못하고,
 * 필드를 추가하면 기존 세션에서는 그 값이 null로 읽힌다(ADR-0019). 생성자의 검사는 세션에서 되살릴 때도 실행되므로,
 * 값이 빠진 채로 되살아난 사용자는 여기서 걸러진다.
 *
 * <p>로그인 시점의 값이다. 계정의 이름·역할·지점을 바꾸면 그 계정의 세션을 끊어 다시 로그인하게 해야 반영된다.
 *
 * @param displayName 화면에 보일 이름
 * @param campusId 소속 지점. 직원만 가진다. 관리자와 연계 학원은 {@code null}
 * @param partnerId 연계 학원 식별자. 연계 학원만 가진다. 그 밖의 역할은 {@code null}
 */
public record AuthenticatedUser(Long id, String loginId, String displayName, UserRole role, Long campusId,
		Long partnerId) implements Principal, Serializable {

	public AuthenticatedUser {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(loginId, "loginId");
		Objects.requireNonNull(displayName, "displayName");
		Objects.requireNonNull(role, "role");
		role.validateCampus(campusId);
		role.validatePartner(partnerId);
	}

	/**
	 * Spring Security와 Spring Session이 "누구의 세션인가"를 가리는 이름. 계정 ID를 쓴다.
	 * 로그에 아이디나 실명이 찍히지 않고, 계정 ID로 그 계정의 세션을 모두 찾을 수 있다.
	 */
	@Override
	public String getName() {
		return String.valueOf(id);
	}

}
