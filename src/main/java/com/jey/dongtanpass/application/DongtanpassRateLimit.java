package com.jey.dongtanpass.application;

import com.jey.core.auth.api.RateLimitName;

/**
 * 동탄고 패스 모듈이 거는 횟수 한도의 이름. 몇 번으로 제한하는지는 설정이 정한다({@code HolderRelinkProperties},
 * {@code PassNoVerifyProperties}, {@code HolderCheckProperties}).
 */
enum DongtanpassRateLimit implements RateLimitName {

	/** 학부모 링크 다시 받기에서 한 접속 주소가 한 번호로 틀린 시도 */
	HOLDER_RELINK("dongtanpass.holder-relink"),

	/** 학부모 링크 다시 받기에서 한 접속 주소가 번호와 무관하게 틀린 시도 */
	HOLDER_RELINK_CLIENT("dongtanpass.holder-relink-client"),

	/** 데스크가 패스 번호로 패스를 확인한 횟수(계정마다, 맞든 틀리든) */
	PASS_NO_VERIFY("dongtanpass.pass-no-verify"),

	/** 데스크가 학생 이름과 학부모 번호를 패스와 미리 맞춰 본 횟수(계정마다, 맞든 틀리든) */
	HOLDER_CHECK("dongtanpass.holder-check"),

	/** 데스크가 패스를 사용 처리한 횟수(계정마다, 맞든 틀리든). 이름·번호 대조 결과로 패스 주인을 훑지 못하게 */
	REDEEM("dongtanpass.redeem");

	private final String code;

	DongtanpassRateLimit(String code) {
		this.code = code;
	}

	@Override
	public String code() {
		return code;
	}

}
