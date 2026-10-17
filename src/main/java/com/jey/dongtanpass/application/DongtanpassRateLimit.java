package com.jey.dongtanpass.application;

import com.jey.core.auth.api.RateLimitName;

/**
 * 동탄고 패스 모듈이 거는 횟수 한도의 이름. 몇 번으로 제한하는지는 설정이 정한다({@code HolderRelinkProperties},
 * {@code PassNoVerifyProperties}).
 */
enum DongtanpassRateLimit implements RateLimitName {

	/** 학부모 링크 다시 받기에서 한 접속 주소가 한 번호로 틀린 시도 */
	HOLDER_RELINK("dongtanpass.holder-relink"),

	/** 학부모 링크 다시 받기에서 한 접속 주소가 번호와 무관하게 틀린 시도 */
	HOLDER_RELINK_CLIENT("dongtanpass.holder-relink-client"),

	/** 데스크가 패스 번호로 패스를 확인한 횟수(계정마다, 맞든 틀리든) */
	PASS_NO_VERIFY("dongtanpass.pass-no-verify");

	private final String code;

	DongtanpassRateLimit(String code) {
		this.code = code;
	}

	@Override
	public String code() {
		return code;
	}

}
