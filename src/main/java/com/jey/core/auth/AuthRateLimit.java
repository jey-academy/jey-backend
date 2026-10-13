package com.jey.core.auth;

import com.jey.core.auth.api.RateLimitName;

/**
 * 인증 기능이 거는 계정별 횟수 한도의 이름. 몇 번으로 제한하는지는 설정이 정한다({@code PasswordChangeLimitProperties}).
 */
enum AuthRateLimit implements RateLimitName {

	/** 본인 비밀번호 변경에서 지금 비밀번호를 틀린 횟수 (ADR-0030) */
	PASSWORD_CHANGE("auth.password-change");

	private final String code;

	AuthRateLimit(String code) {
		this.code = code;
	}

	@Override
	public String code() {
		return code;
	}

}
