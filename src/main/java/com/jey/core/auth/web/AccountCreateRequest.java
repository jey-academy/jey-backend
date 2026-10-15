package com.jey.core.auth.web;

import com.jey.core.auth.NewAccount;
import com.jey.core.auth.api.AccountRules;
import com.jey.core.auth.api.UserRole;
import com.jey.core.auth.domain.User;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "계정 만들기. 관리자와 직원만 만들 수 있다")
record AccountCreateRequest(
		@Schema(description = "로그인 아이디. 영문 소문자, 숫자, 점, 밑줄, 하이픈으로 3~50자이고 첫 글자는 영문이나 숫자다. 만든 뒤에는 바꿀 수 없다",
				example = "desk.dongtan") @NotBlank @Pattern(regexp = User.LOGIN_ID_PATTERN,
						message = "영문 소문자, 숫자, 점, 밑줄, 하이픈으로 3~50자여야 합니다.") String loginId,
		@Schema(description = "화면에 보일 이름") @NotBlank @Size(max = User.MAX_NAME_LENGTH) String name,
		@Schema(description = "처음 비밀번호. 10자 이상 72자 이하이고(한글은 24자까지) 아이디와 달라야 한다. 받은 사람이 로그인한 뒤 직접 바꾼다") @NotBlank @Size(
				max = AccountRules.MAX_PASSWORD_INPUT_LENGTH) String password,
		@Schema(description = "ADMIN 또는 STAFF. PARTNER는 만들 수 없다") @NotNull UserRole role,
		@Schema(description = "소속 지점. STAFF면 반드시 넣고 ADMIN이면 넣지 않는다", nullable = true) Long campusId) {

	NewAccount toNewAccount() {
		return new NewAccount(loginId, name, password, role, campusId);
	}

	// 비밀번호가 로그에 찍히지 않게 한다.
	@Override
	public String toString() {
		return "AccountCreateRequest[loginId=" + loginId + ", role=" + role + ", campusId=" + campusId + "]";
	}

}
