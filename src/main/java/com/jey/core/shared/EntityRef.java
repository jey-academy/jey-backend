package com.jey.core.shared;

import java.util.regex.Pattern;

/**
 * 무엇에 대한 일인지를 종류와 ID로 가리킨다. 감사 로그의 대상, 도메인 이벤트의 대상으로 쓴다.
 *
 * @param type 소문자 kebab-case(예: {@code student}, {@code guardian}). 한 번 쓴 이름은 바꾸지 않는다. 조회 조건으로 쓰인다
 */
public record EntityRef(String type, long id) {

	public static final int MAX_TYPE_LENGTH = 40;

	private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");

	/**
	 * @throws IllegalArgumentException 종류의 형식이 틀리거나 ID가 양수가 아닐 때
	 */
	public EntityRef {
		if (type == null || type.length() > MAX_TYPE_LENGTH || !TYPE.matcher(type).matches()) {
			throw new IllegalArgumentException("대상 종류는 " + MAX_TYPE_LENGTH + "자 이하의 소문자 kebab-case여야 한다: " + type);
		}
		if (id <= 0) {
			throw new IllegalArgumentException("대상 ID는 양수여야 한다: " + id);
		}
	}

	public static EntityRef of(String type, long id) {
		return new EntityRef(type, id);
	}

}
