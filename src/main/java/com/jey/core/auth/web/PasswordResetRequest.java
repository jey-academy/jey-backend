package com.jey.core.auth.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "관리자가 다른 계정의 비밀번호를 다시 정한다")
record PasswordResetRequest(
		@Schema(description = "새 비밀번호. 10자 이상 72자 이하이고(한글은 24자까지) 아이디와 달라야 한다") @NotBlank @Size(max = 100) String password) {

	// 비밀번호가 로그에 찍히지 않게 한다.
	@Override
	public String toString() {
		return "PasswordResetRequest[]";
	}

}
