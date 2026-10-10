package com.jey.core.auth.web;

import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;

/**
 * @param campusId 소속 지점. 지점이 없는 계정(관리자, 연계 학원)은 {@code null}이다.
 * 필드를 빼지 않고 null로 내보내서 응답의 모양이 항상 같게 한다.
 */
record MeResponse(Long id, String loginId, String name, UserRole role, Long campusId) {

	static MeResponse from(AuthenticatedUser user) {
		return new MeResponse(user.id(), user.loginId(), user.displayName(), user.role(), user.campusId());
	}

}
