package com.jey.core.shared;

import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 휴대전화 번호. 숫자만 남긴 한 가지 형태({@code 01012345678})로 갖는다.
 * 알림과 패스 링크를 보낼 번호라서 유선 전화와 대표 번호는 받지 않는다.
 *
 * <p>원문은 {@link #value()}를 직접 불러야 나온다. {@code toString()}과 JSON 출력은 가린 표시({@code 010-****-5678})다.
 * 로그에 찍거나 응답 타입에 그대로 넣어도 원문이 나가지 않게 하기 위해서다.
 * DB에는 이 타입을 그대로 저장하지 않고 {@link PhoneProtector}로 바꾼 {@link ProtectedPhone}을 저장한다.
 *
 * @param value 숫자만 남긴 번호. 010은 11자리, 011·016·017·018·019는 10~11자리
 */
public record PhoneNumber(String value) {

	private static final Pattern SEPARATORS = Pattern.compile("[\\s.-]");

	private static final Pattern MOBILE = Pattern.compile("010\\d{8}|01[16789]\\d{7,8}");

	private static final String COUNTRY_CODE = "+82";

	private static final int LAST_DIGITS = 4;

	private static final int AREA_DIGITS = 3;

	/**
	 * @throws IllegalArgumentException 휴대전화 번호가 아닐 때. 문구에 입력값을 싣지 않는다
	 */
	public PhoneNumber {
		value = normalize(value);
	}

	// 레코드는 기본이 속성 방식({"value": …})이라, 문자열 하나에서 읽으려면 DELEGATING을 명시해야 한다.
	@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
	public static PhoneNumber of(String value) {
		return new PhoneNumber(value);
	}

	/** 뒷자리 4자리. 데스크에서 번호를 찾을 때 쓴다. */
	public String last4() {
		return value.substring(value.length() - LAST_DIGITS);
	}

	/** 가운데를 가린 표시({@code 010-****-5678}). 화면과 로그에는 이것을 쓴다. */
	@JsonValue
	public String masked() {
		int hidden = value.length() - AREA_DIGITS - LAST_DIGITS;
		return value.substring(0, AREA_DIGITS) + "-" + "*".repeat(hidden) + "-" + last4();
	}

	@Override
	public String toString() {
		return masked();
	}

	// 틀린 입력도 누군가의 전화번호일 가능성이 크다. 예외 문구는 로그에 남으므로 입력값을 넣지 않는다.
	private static String normalize(String input) {
		if (input == null || input.isBlank()) {
			throw new IllegalArgumentException("전화번호는 비울 수 없다");
		}
		String digits = SEPARATORS.matcher(input).replaceAll("");
		if (digits.startsWith(COUNTRY_CODE)) {
			String national = digits.substring(COUNTRY_CODE.length());
			digits = national.startsWith("0") ? national : "0" + national;
		}
		if (!MOBILE.matcher(digits).matches()) {
			throw new IllegalArgumentException("휴대전화 번호 형식이 아니다");
		}
		return digits;
	}

}
