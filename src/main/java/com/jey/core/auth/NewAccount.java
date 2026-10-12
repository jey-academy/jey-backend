package com.jey.core.auth;

import java.util.Objects;

import com.jey.core.auth.api.UserRole;

/**
 * 만들 계정.
 *
 * @param password 처음 비밀번호(원문). 저장할 때 해시한다
 * @param campusId 소속 지점. 직원이면 있어야 하고 관리자면 없어야 한다
 */
public record NewAccount(String loginId, String name, String password, UserRole role, Long campusId) {

	// 값이 맞는지는 서비스가 본다. 여기서는 빠진 값만 막는다(빠진 채로 가면 뜻 모를 자리에서 실패한다).
	public NewAccount {
		Objects.requireNonNull(loginId, "loginId");
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(password, "password");
		Objects.requireNonNull(role, "role");
	}

	// 비밀번호가 로그에 찍히지 않게 한다.
	@Override
	public String toString() {
		return "NewAccount[loginId=" + loginId + ", role=" + role + ", campusId=" + campusId + "]";
	}

}
