package com.jey.core.auth.web;

import com.jey.core.auth.AccountChange;
import com.jey.core.auth.api.UserRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "계정 고치기. 통째로 바꾼다: 그대로 둘 값도 지금 값을 넣어 보낸다. 아이디와 비밀번호는 여기서 바꾸지 않는다")
record AccountUpdateRequest(
		@Schema(description = "화면에 보일 이름. 로그인돼 있는 화면에는 다음 로그인부터 보인다") @NotBlank @Size(max = 50) String name,
		@Schema(description = "바꾸면 그 계정은 로그아웃된다. PARTNER로 바꾸거나 PARTNER에서 바꿀 수 없다") @NotNull UserRole role,
		@Schema(description = "소속 지점. STAFF면 반드시 넣고 그 밖의 역할이면 넣지 않는다. 바꾸면 그 계정은 로그아웃된다",
				nullable = true) Long campusId) {

	AccountChange toChange() {
		return new AccountChange(name, role, campusId);
	}

}
