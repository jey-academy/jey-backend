package com.jey.core.auth.web;

import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/**
 * @param campusId 소속 지점. 지점이 없는 계정(관리자, 연계 학원)은 {@code null}이다.
 * 필드를 빼지 않고 null로 내보내서 응답의 모양이 항상 같게 한다.
 * 연계 학원 식별자는 화면이 쓸 곳이 없어 내보내지 않는다.
 */
record MeResponse(@Schema(requiredMode = RequiredMode.REQUIRED) Long id,
		@Schema(requiredMode = RequiredMode.REQUIRED) String loginId,
		@Schema(description = "화면에 보일 이름", requiredMode = RequiredMode.REQUIRED) String name,
		@Schema(requiredMode = RequiredMode.REQUIRED) UserRole role,
		@Schema(description = "소속 지점. 직원만 값이 있고 관리자와 연계 학원은 null이다. 필드는 항상 있다",
				requiredMode = RequiredMode.REQUIRED, nullable = true) Long campusId) {

	static MeResponse from(AuthenticatedUser user) {
		return new MeResponse(user.id(), user.loginId(), user.displayName(), user.role(), user.campusId());
	}

}
