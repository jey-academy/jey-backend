package com.jey.core.member.web;

import com.jey.core.shared.PhoneNumber;
import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * 가리지 않은 전화번호. 다른 응답의 전화번호는 {@link PhoneNumber} 타입이라 가린 표시로 나가고, 이것만 문자열로 전체를 싣는다.
 */
record PhoneRevealResponse(
		@Schema(requiredMode = REQUIRED, description = "가리지 않은 전체 번호. 하이픈을 넣은 표기다. 화면에 보여 주기만 하고 저장하지 않는다",
				example = "010-1234-5678") String phone) {

	static PhoneRevealResponse from(PhoneNumber phone) {
		String digits = phone.value();
		int lastStart = digits.length() - 4;
		return new PhoneRevealResponse(
				digits.substring(0, 3) + "-" + digits.substring(3, lastStart) + "-" + digits.substring(lastStart));
	}

	// 기본 구현은 번호를 그대로 찍는다. 로그나 예외 문구에 실려도 번호가 나가지 않게 한다. JSON 응답과는 무관하다.
	@Override
	public String toString() {
		return "PhoneRevealResponse[phone=(가림)]";
	}

}
