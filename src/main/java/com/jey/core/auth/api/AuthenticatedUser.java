package com.jey.core.auth.api;

import java.io.Serializable;
import java.security.Principal;
import java.util.Objects;

import com.jey.core.shared.BusinessException;

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
 * @param campusId 소속 지점. 직원만 가진다. 관리자와 연계 학원은 {@code null}.
 *     {@code null}이 "전 지점"을 뜻하지 않는다(연계 학원도 {@code null}이다).
 *     다룰 수 있는 지점은 이 값을 직접 보지 말고 {@link #campusScope()}로 판단한다.
 * @param partnerId 연계 학원 식별자. 연계 학원만 가진다. 그 밖의 역할은 {@code null}
 */
public record AuthenticatedUser(Long id, String loginId, String displayName, UserRole role, Long campusId,
		Long partnerId) implements Principal, Serializable {

	public AuthenticatedUser {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(loginId, "loginId");
		Objects.requireNonNull(displayName, "displayName");
		Objects.requireNonNull(role, "role");
		role.validateAffiliation(campusId, partnerId);
	}

	/**
	 * Spring Security와 Spring Session이 "누구의 세션인가"를 가리는 이름. 계정 ID를 쓴다.
	 * 로그에 아이디나 실명이 찍히지 않고, 계정 ID로 그 계정의 세션을 모두 찾을 수 있다.
	 */
	@Override
	public String getName() {
		return String.valueOf(id);
	}

	/**
	 * 소속 지점으로 묶은 자료에서 이 사용자가 다룰 수 있는 지점. 관리자는 전 지점, 직원은 소속 지점이다.
	 * 묶지 않은 자료(직원이 전 지점을 다루는 자료)에는 부르지 않는다.
	 *
	 * @throws BusinessException 지점 자료를 다룰 수 없는 계정일 때({@code AUTH_CAMPUS_NOT_ALLOWED})
	 */
	public CampusScope campusScope() {
		// 역할이 늘면 여기서 컴파일이 멈춘다. 새 역할의 범위를 정하지 않은 채 넘어가지 않게 default를 두지 않는다.
		return switch (role) {
			case ADMIN -> new CampusScope.All();
			case STAFF -> new CampusScope.Only(campusId);
			case PARTNER -> throw new BusinessException(AccessErrorCode.CAMPUS_NOT_ALLOWED);
		};
	}

	/**
	 * 목록 조회에 쓴다. 화면이 고른 지점으로 범위를 좁힌다.
	 * 단건 확인에는 쓰지 않는다. {@code null}을 넘기면 확인 없이 허용 범위를 돌려주므로, 자료의 지점은 {@link #requireCampus}로 확인한다.
	 * 직원이 다른 지점을 고르면 소속 지점으로 바꿔 주지 않고 거부한다. 바꿔 주면 고른 지점의 자료처럼 보인다.
	 *
	 * @param requestedCampusId 화면이 고른 지점. 고르지 않았으면 {@code null}
	 * @throws BusinessException 다룰 수 없는 지점을 골랐을 때({@code AUTH_CAMPUS_NOT_ALLOWED})
	 */
	public CampusScope campusScopeFor(Long requestedCampusId) {
		CampusScope allowed = campusScope();
		if (requestedCampusId == null) {
			return allowed;
		}
		requireCampus(requestedCampusId);
		return new CampusScope.Only(requestedCampusId);
	}

	/**
	 * 단건 조회와 변경에 쓴다. 자료가 속한 지점을 이 사용자가 다룰 수 있는지 확인한다.
	 *
	 * @throws BusinessException 다룰 수 없는 지점일 때({@code AUTH_CAMPUS_NOT_ALLOWED})
	 */
	public void requireCampus(long campusId) {
		if (!campusScope().includes(campusId)) {
			throw new BusinessException(AccessErrorCode.CAMPUS_NOT_ALLOWED);
		}
	}

}
