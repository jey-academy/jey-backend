package com.jey.dongtanpass.domain;

import java.text.Normalizer;

/**
 * 패스의 학생 이름과 맞춰 보는 대조 값(ADR-0035). 학부모가 링크를 다시 받을 때 쓰고, 사용 처리의 확인(이슈 6)도 이것을 쓴다.
 *
 * <p>NFC로 모으고 공백을 모두 뺀다. 기기에 따라 한글이 자모로 풀려 들어오는 일과 띄어쓰기 차이를 흡수한다.
 * 공백은 {@link Character#isWhitespace}(탭, 줄바꿈, 전각 공백)와 {@link Character#isSpaceChar}(줄바꿈하지 않는 공백 등)를 모두 뺀다.
 * 다른 글자는 다른 이름이다. 0폭 공백(U+200B) 같은 형식 문자도 공백이 아니라 글자로 본다.
 */
public final class StudentNameKey {

	private final String key;

	private StudentNameKey(String key) {
		this.key = key;
	}

	/**
	 * @throws IllegalArgumentException 이름이 없거나 공백뿐일 때
	 */
	public static StudentNameKey of(String name) {
		if (name == null) {
			throw new IllegalArgumentException("이름은 비울 수 없다");
		}
		String key = normalize(name);
		if (key.isEmpty()) {
			throw new IllegalArgumentException("이름은 공백뿐일 수 없다");
		}
		return new StudentNameKey(key);
	}

	/** 저장된 이름이 같은 이름인지. 저장된 이름이 없으면 맞지 않는다. */
	public boolean matches(String storedName) {
		return storedName != null && key.equals(normalize(storedName));
	}

	private static String normalize(String name) {
		StringBuilder letters = new StringBuilder();
		Normalizer.normalize(name, Normalizer.Form.NFC)
			.codePoints()
			.filter(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c))
			.forEach(letters::appendCodePoint);
		return letters.toString();
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof StudentNameKey that && key.equals(that.key);
	}

	@Override
	public int hashCode() {
		return key.hashCode();
	}

	// 학생 실명이 로그에 찍히지 않게 한다.
	@Override
	public String toString() {
		return "StudentNameKey[***]";
	}

}
