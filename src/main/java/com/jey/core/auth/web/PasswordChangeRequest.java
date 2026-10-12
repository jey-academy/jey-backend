package com.jey.core.auth.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "로그인한 사람이 자기 비밀번호를 바꾼다")
record PasswordChangeRequest(@Schema(description = "지금 비밀번호") @NotBlank @Size(max = 100) String currentPassword,
		@Schema(description = "새 비밀번호. 10자 이상 72자 이하이고(한글은 24자까지) 아이디, 지금 비밀번호와 달라야 한다") @NotBlank @Size(
				max = 100) String newPassword) {

	// 비밀번호가 로그에 찍히지 않게 한다.
	@Override
	public String toString() {
		return "PasswordChangeRequest[]";
	}

}
