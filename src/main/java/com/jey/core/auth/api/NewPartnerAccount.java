package com.jey.core.auth.api;

import java.util.Objects;

/**
 * 만들 연계 학원 계정.
 *
 * @param partnerId 소속 연계 학원. 만든 뒤에는 바뀌지 않는다
 * @param password 처음 비밀번호(원문). 저장할 때 해시한다
 */
public record NewPartnerAccount(long partnerId, String loginId, String name, String password) {

	// 값이 맞는지는 서비스가 본다. 여기서는 빠진 값만 막는다(빠진 채로 가면 뜻 모를 자리에서 실패한다).
	public NewPartnerAccount {
		Objects.requireNonNull(loginId, "loginId");
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(password, "password");
	}

	// 비밀번호가 로그에 찍히지 않게 한다.
	@Override
	public String toString() {
		return "NewPartnerAccount[partnerId=" + partnerId + ", loginId=" + loginId + "]";
	}

}
