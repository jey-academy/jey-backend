package com.jey.core.auth.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;

/**
 * @param campusId 소속 지점. 지점이 없는 계정(관리자, 연계 학원)은 응답에서 빠진다.
 */
record MeResponse(Long id, String loginId, String name, UserRole role,
		@JsonInclude(JsonInclude.Include.NON_NULL) Long campusId) {

	static MeResponse from(AuthenticatedUser user) {
		return new MeResponse(user.id(), user.loginId(), user.name(), user.role(), user.campusId());
	}

}
