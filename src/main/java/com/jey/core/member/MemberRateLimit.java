package com.jey.core.member;

import com.jey.core.auth.api.RateLimitName;

/**
 * 학생·학부모 기능이 거는 계정별 횟수 한도의 이름 (ADR-0026). 몇 번으로 제한하는지는 설정이 정한다({@code MemberLimitProperties}).
 */
enum MemberRateLimit implements RateLimitName {

	/** 전체 전화번호 보기 */
	PHONE_REVEAL("member.phone-reveal"),

	/** 전화번호가 실린 변경 요청. 성공해도 센다 */
	PHONE_CHANGE("member.phone-change"),

	/** "그 번호가 이미 있다"가 드러나는 거부 응답 */
	PHONE_PROBE("member.phone-probe");

	private final String code;

	MemberRateLimit(String code) {
		this.code = code;
	}

	@Override
	public String code() {
		return code;
	}

}
