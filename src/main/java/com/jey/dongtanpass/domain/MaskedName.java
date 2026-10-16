package com.jey.dongtanpass.domain;

import java.text.Normalizer;

/**
 * 학부모 화면에 보여 줄 가린 학생 이름. 그 화면은 링크만 있으면 누구나 열 수 있어 이름을 다 보여 주지 않는다.
 *
 * <p>NFC로 모으고 공백(전각 포함)을 모두 뺀 뒤 글자(코드 포인트) 수로 정한다. 한 글자는 그대로, 두 글자는 끝 글자를 가리고,
 * 세 글자 이상은 첫 글자와 끝 글자만 남긴다(김하윤 → 김*윤, 남궁민수 → 남**수).
 */
public final class MaskedName {

	private static final String MASK = "*";

	private MaskedName() {
	}

	public static String of(String name) {
		// 자모로 풀린(NFD) 한글은 음절로 모은 뒤 센다. 그러지 않으면 자모 하나만 보이고 길이가 늘어난다.
		int[] letters = Normalizer.normalize(name, Normalizer.Form.NFC)
			.codePoints().filter(c -> !Character.isWhitespace(c)).toArray();
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
