package com.jey.core.audit;

import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 글 안에 휴대전화 번호처럼 보이는 것이 있는지 본다. 감사 로그에 전화번호가 실리지 않게 하는 마지막 그물이다.
 *
 * <p>두 가지를 찾는다.
 * <ol>
 * <li>흔히 쓰는 모양: {@code 010-1234-5678}처럼 "01x, 3~4자리, 4자리" 세 묶음. 묶음 사이에 기호가 있거나 없다.
 * 국가 번호({@code +82})가 붙은 것과 가운데를 가린 것({@code 010-****-5678}, 뒷자리가 남는다)도 찾는다.</li>
 * <li>흩어 쓴 모양: 숫자와 공백·점·하이픈만으로 된 구간이 통째로 휴대전화 번호인 경우({@code 01 0 1234 5678}).
 * 전화번호 값 객체가 이런 입력도 번호로 받아들이므로 여기서도 번호로 본다.</li>
 * </ol>
 *
 * <p>숫자가 많다고 번호로 보지 않는다. IP 주소({@code 192.168.10.11}), 날짜 둘({@code 2026-11-01/2026-11-30}),
 * 0으로 시작하지 않는 숫자열은 번호가 아니다. 이런 값을 번호로 보면 그것을 싣는 정상 기록이 실패한다.
 * 반대로 0으로 시작하는 10~11자리 숫자 구간은 번호가 아니어도 번호로 본다({@code 016-2026-0001} 같은 일련번호,
 * {@code 0.1666666667} 같은 소수). 그런 값은 숫자로 싣거나 모양을 바꿔 싣는다.
 *
 * <p>앞뒤가 영숫자에 붙어 있으면 보지 않는다. UUID나 주문번호 같은 식별자 안의 숫자 구간이 걸리지 않게 하기 위해서다.
 * 그래서 영문자에 바로 붙여 쓴 번호({@code tel01012345678})는 놓친다. 띄어 쓴 것({@code tel 010-1234-5678})은 잡는다.
 */
final class PhoneLikeText {

	// 묶음 사이에 넣는 기호. 세 글자까지(예: " - ").
	private static final String GAP = "[\\s.\\-/_·()]{0,3}";

	private static final String NOT_AFTER_ALNUM = "(?<![0-9A-Za-z])";

	private static final String NOT_BEFORE_ALNUM = "(?![0-9A-Za-z])";

	// 국내 표기는 0으로 시작한다. 국가 번호가 붙으면 0이 있어도 없어도 되고, 괄호로 감싼 0도 쓴다(+82 (0)10-…).
	private static final String PREFIX = "(?:\\+82" + GAP + "(?:\\(?0\\)?)?|\\(?0)1[016789]\\)?";

	private static final Pattern GROUPED = Pattern
			.compile(NOT_AFTER_ALNUM + PREFIX + GAP + "\\d{3,4}" + GAP + "\\d{4}" + NOT_BEFORE_ALNUM);

	private static final Pattern MASKED = Pattern
			.compile(NOT_AFTER_ALNUM + PREFIX + GAP + "\\*{3,4}" + GAP + "\\d{4}" + NOT_BEFORE_ALNUM);

	// 숫자로 시작해 숫자로 끝나고, 사이에는 숫자와 공백·점·하이픈만 있는 구간.
	private static final Pattern DIGIT_RUN = Pattern
			.compile(NOT_AFTER_ALNUM + "\\d(?:[\\d\\s.\\-]*\\d)?" + NOT_BEFORE_ALNUM);

	private static final Pattern RUN_SEPARATORS = Pattern.compile("[\\s.\\-]");

	// 전화번호 값 객체가 받는 번호와 같은 모양.
	private static final Pattern MOBILE = Pattern.compile("010\\d{8}|01[16789]\\d{7,8}");

	private PhoneLikeText() {
	}

	static boolean found(String text) {
		// 전각 숫자와 기호를 반각으로 바꾼 것도 본다. 원문도 따로 본다.
		// 바꾼 것만 보면, 원문에서는 기호였던 글자(℡, №, ①)가 영숫자로 풀리면서 옆의 번호가 식별자처럼 보여 놓친다.
		return foundIn(text) || foundIn(Normalizer.normalize(text, Normalizer.Form.NFKC));
	}

	private static boolean foundIn(String text) {
		if (GROUPED.matcher(text).find() || MASKED.matcher(text).find()) {
			return true;
		}
		Matcher runs = DIGIT_RUN.matcher(text);
		while (runs.find()) {
			String digits = RUN_SEPARATORS.matcher(runs.group()).replaceAll("");
			if (MOBILE.matcher(digits).matches()) {
				return true;
			}
			// 국가 번호를 붙여 흩어 쓴 것(+82 1 0 1234 5678, + 82 10 1234 5678). +가 앞에 있을 때만 82를 국가 번호로 본다.
			if (digits.startsWith("82") && followsPlus(text, runs.start())) {
				String national = digits.substring(2);
				if (MOBILE.matcher(national.startsWith("0") ? national : "0" + national).matches()) {
					return true;
				}
			}
		}
		return false;
	}

	// 구간 앞의 공백·점·하이픈을 건너뛰고 +가 있는지 본다. 전화번호 값 객체는 +와 숫자 사이의 기호도 지우고 읽는다.
	private static boolean followsPlus(String text, int runStart) {
		int index = runStart - 1;
		while (index >= 0 && RUN_SEPARATORS.matcher(String.valueOf(text.charAt(index))).matches()) {
			index--;
		}
		return index >= 0 && text.charAt(index) == '+';
	}

}
