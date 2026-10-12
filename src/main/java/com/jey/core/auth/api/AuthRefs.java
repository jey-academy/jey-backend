package com.jey.core.auth.api;

import com.jey.core.shared.EntityRef;

/**
 * 로그인 계정을 가리키는 대상. 감사 로그의 대상으로 쓴다.
 */
public final class AuthRefs {

	public static final String ACCOUNT = "account";

	private AuthRefs() {
	}

	public static EntityRef account(long accountId) {
		return EntityRef.of(ACCOUNT, accountId);
	}

}
