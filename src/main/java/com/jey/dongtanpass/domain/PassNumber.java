package com.jey.dongtanpass.domain;

import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/**
 * 패스 번호. {@code DT-}와 여섯 글자다(예: {@code DT-K7M2QX}). 데스크에서 QR이 안 될 때 손으로 친다.
 * 무작위라 발급 순서나 건수가 드러나지 않는다. 겹치면 발급하는 쪽이 다시 만든다(유니크 키가 막는다).
 */
public final class PassNumber {

	/** 쓰는 글자. 눈으로 헷갈리는 0/O, 1/I는 뺐다. 32글자라 여섯 자리면 약 10억 가지다. */
	public static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

	private static final String PREFIX = "DT-";

	private static final int LENGTH = 6;

	private static final Pattern FORMAT = Pattern.compile(PREFIX + "[" + ALPHABET + "]{" + LENGTH + "}");

	private PassNumber() {
	}

	public static String generate(RandomGenerator random) {
		StringBuilder number = new StringBuilder(PREFIX);
		for (int i = 0; i < LENGTH; i++) {
			number.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
		}
		return number.toString();
	}

	/** 형식에 맞는지. 대문자만 받는다(소문자로 친 것을 고쳐 주는 것은 받는 쪽의 일이다). */
	public static boolean isValid(String value) {
		return value != null && FORMAT.matcher(value).matches();
	}

}
