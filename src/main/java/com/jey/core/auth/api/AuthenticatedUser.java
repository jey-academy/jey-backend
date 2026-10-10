package com.jey.core.auth.api;

import java.io.Serializable;

/**
 * 로그인한 사용자. 세션에 저장되므로 비밀번호나 해시를 담지 않는다.
 *
 * @param campusId 소속 지점. 전 지점을 보는 관리자와 연계 학원은 {@code null}
 */
public record AuthenticatedUser(Long id, String loginId, String name, UserRole role, Long campusId)
		implements Serializable {
}
