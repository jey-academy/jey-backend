package com.jey.dongtanpass.api;

import com.jey.core.shared.EntityRef;

/**
 * 패스를 가리키는 대상. 감사 로그와 이벤트의 대상으로 쓴다.
 * 종류 이름은 이미 쌓인 기록에 적혀 있고 조회 조건으로 쓰이므로 바꾸지 않는다.
 */
public final class PassRefs {

	public static final String PASS = "pass";

	private PassRefs() {
	}

	public static EntityRef pass(long passId) {
		return EntityRef.of(PASS, passId);
	}

}
