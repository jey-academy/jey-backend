package com.jey.core.auth.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 입력 크기를 제한한다. BCrypt는 비밀번호의 앞 72바이트까지만 쓰므로, 비밀번호를 새로 정하는 쪽의 제한은 그에 맞춰야 한다.
record LoginRequest(@NotBlank @Size(max = 50) String loginId, @NotBlank @Size(max = 100) String password) {

	// 값이 로그에 찍히지 않게 한다. 아이디 칸에 비밀번호를 잘못 넣는 일이 흔해서 아이디도 남기지 않는다.
	@Override
	public String toString() {
		return "LoginRequest[]";
	}

}
