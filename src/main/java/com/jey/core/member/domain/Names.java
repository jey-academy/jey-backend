package com.jey.core.member.domain;

/** 학생과 학부모 이름의 공통 규칙. */
final class Names {

	private Names() {
	}

	/**
	 * @return 앞뒤 공백을 뗀 이름
	 * @throws IllegalArgumentException 비었거나 길 때. 문구에 이름을 싣지 않는다
	 */
	static String valid(String name, int maxLength) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("name은 비울 수 없다");
		}
		String trimmed = name.strip();
		if (trimmed.length() > maxLength) {
			throw new IllegalArgumentException("name은 " + maxLength + "자를 넘을 수 없다");
		}
		return trimmed;
	}

}
