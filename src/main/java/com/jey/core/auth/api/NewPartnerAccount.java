package com.jey.core.auth.api;

import java.util.Objects;

/**
 * 만들 연계 학원 계정.
 *
 * @param partnerId 소속 연계 학원. 양수다. 만든 뒤에는 바뀌지 않는다
 * @param password 처음 비밀번호(원문). 저장할 때 해시한다
 */
public record NewPartnerAccount(long partnerId, String loginId, String name, String password) {

	/**
	 * 값이 맞는지(아이디 형식, 비밀번호 규칙)는 서비스가 본다. 여기서는 빠진 값과 식별자가 될 수 없는 값만 막는다.
	 *
	 * @throws IllegalArgumentException 연계 학원 식별자가 양수가 아닐 때. 연계 학원 테이블은 다른 모듈에 있어 외래 키가 걸러 주지 않는다
	 */
	public NewPartnerAccount {
		if (partnerId <= 0) {
			throw new IllegalArgumentException("partnerId는 양수여야 한다: " + partnerId);
		}
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
