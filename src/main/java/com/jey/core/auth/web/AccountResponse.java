package com.jey.core.auth.web;

import java.time.Instant;

import com.jey.core.auth.AccountView;
import com.jey.core.auth.api.UserRole;
import com.jey.core.auth.domain.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "로그인 계정 하나. 비밀번호는 어떤 응답에도 없다")
record AccountResponse(@Schema(requiredMode = RequiredMode.REQUIRED) Long id,
		@Schema(description = "로그인 아이디. 만든 뒤에는 바꿀 수 없다", requiredMode = RequiredMode.REQUIRED) String loginId,
		@Schema(description = "화면에 보일 이름", requiredMode = RequiredMode.REQUIRED) String name,
		@Schema(requiredMode = RequiredMode.REQUIRED) UserRole role,
		@Schema(description = "소속 지점. 직원만 값이 있고 관리자와 연계 학원은 null이다. 필드는 항상 있다",
				requiredMode = RequiredMode.REQUIRED, nullable = true) Long campusId,
		@Schema(description = "연계 학원 식별자. 연계 학원 계정만 값이 있고 그 밖에는 null이다. 필드는 항상 있다",
				requiredMode = RequiredMode.REQUIRED, nullable = true) Long partnerId,
		@Schema(description = "DISABLED면 차단된 계정이다. 로그인할 수 없다", requiredMode = RequiredMode.REQUIRED) UserStatus status,
		@Schema(description = "계정을 만든 시각", requiredMode = RequiredMode.REQUIRED) Instant createdAt) {

	static AccountResponse from(AccountView account) {
		return new AccountResponse(account.id(), account.loginId(), account.name(), account.role(),
				account.campusId(), account.partnerId(), account.status(), account.createdAt());
	}

}
