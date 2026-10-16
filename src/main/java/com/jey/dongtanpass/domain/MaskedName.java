package com.jey.dongtanpass.domain;

/**
 * 학부모 화면에 보여 줄 가린 학생 이름. 그 화면은 링크만 있으면 누구나 열 수 있어 이름을 다 보여 주지 않는다.
 *
 * <p>공백(전각 포함)을 모두 빼고 글자(코드 포인트) 수로 정한다. 한 글자는 그대로, 두 글자는 끝 글자를 가리고,
 * 세 글자 이상은 첫 글자와 끝 글자만 남긴다(김하윤 → 김*윤, 남궁민수 → 남**수).
 */
public final class MaskedName {

	private static final String MASK = "*";

	private MaskedName() {
	}

	public static String of(String name) {
		int[] letters = name.codePoints().filter(c -> !Character.isWhitespace(c)).toArray();
		if (letters.length <= 1) {
			return new String(letters, 0, letters.length);
		}
		StringBuilder masked = new StringBuilder().appendCodePoint(letters[0]);
		if (letters.length == 2) {
			return masked.append(MASK).toString();
		}
		return masked.append(MASK.repeat(letters.length - 2)).appendCodePoint(letters[letters.length - 1]).toString();
	}

}
