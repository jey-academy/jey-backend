package com.jey.core.auth.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 비밀번호 길이를 제한한다. 아주 긴 값을 해시하게 만들어 서버 자원을 쓰게 하는 요청을 막는다.
record LoginRequest(@NotBlank @Size(max = 50) String loginId, @NotBlank @Size(max = 100) String password) {

	// 비밀번호가 로그에 찍히지 않게 한다.
	@Override
	public String toString() {
		return "LoginRequest[loginId=" + loginId + "]";
	}

}
