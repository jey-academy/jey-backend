package com.jey.dongtanpass.api;

import com.jey.core.shared.EntityRef;

/**
 * 연계 학원을 가리키는 대상. 감사 로그의 대상으로 쓴다.
 * 종류 이름은 이미 쌓인 기록에 적혀 있고 조회 조건으로 쓰이므로 바꾸지 않는다.
 */
public final class PartnerRefs {

	public static final String PARTNER = "partner";

	private PartnerRefs() {
	}

	public static EntityRef partner(long partnerId) {
		return EntityRef.of(PARTNER, partnerId);
	}

}
