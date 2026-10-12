package com.jey.core.auth;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 비밀번호로 받을 수 있는 값의 규칙 (ADR-0030). 계정을 만들 때, 비밀번호를 바꿀 때, 첫 관리자를 만들 때 모두 이것 하나를 쓴다.
 *
 * <p>길이를 본다. 글자 종류(대문자, 특수문자)를 강제하지 않는다. 받지 않는 글자는 제어 문자뿐이다.
 * 비밀번호 자체는 여기에 남기지 않는다(무엇을 어겼는지만 돌려준다).
 *
 * <p>같은 글자를 다르게 적은 값(한글의 조합형과 완성형)은 맞추지 않는다. 정한 때와 다른 방식으로 입력하면 다른 비밀번호다.
 */
public final class PasswordPolicy {

	public static final int MIN_LENGTH = 10;

	/** BCrypt는 72바이트까지만 읽는다. 넘는 부분은 조용히 버려지므로 넘으면 받지 않는다. 한글은 한 글자가 3바이트다. */
	public static final int MAX_BYTES = 72;

	// 눈에 보이는 글자 하나. 공백류(\p{Z}), 제어·서식 문자(\p{C} — 폭 없는 공백 포함)가 아닌 것.
	private static final Pattern VISIBLE = Pattern.compile("[^\\p{Z}\\p{C}]");

	private PasswordPolicy() {
	}

	/**
	 * 길이, 쓸 수 없는 글자, 아이디를 그대로 비밀번호로 쓰지 않았는지를 본다.
	 *
	 * @param loginId 그 계정의 아이디. 대소문자와 앞뒤 공백은 가리지 않고 비교한다. 아이디와 비교할 일이 없으면 {@code null}
	 * @return 어긴 규칙. 받을 수 있는 값이면 비어 있다
	 */
	public static Optional<Violation> check(String password, String loginId) {
		if (password == null || VISIBLE.matcher(password).results().count() < MIN_LENGTH) {
			return Optional.of(Violation.TOO_SHORT);
		}
		if (password.chars().anyMatch(Character::isISOControl)) {
			return Optional.of(Violation.CONTROL_CHARACTER);
		}
		if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
			return Optional.of(Violation.TOO_LONG);
		}
		if (loginId != null && password.trim().equalsIgnoreCase(loginId.trim())) {
			return Optional.of(Violation.SAME_AS_LOGIN_ID);
		}
		return Optional.empty();
	}

	public enum Violation {

		/** 비었거나, 눈에 보이는 글자가 {@link #MIN_LENGTH}자보다 적다. 공백이나 폭 없는 글자로 길이만 채운 값도 여기에 든다. */
		TOO_SHORT,
		/**
		 * 제어 문자(널 문자, 줄바꿈, 탭 등)가 있다. 해시 함수는 널 문자에서 읽기를 멈춘다.
		 * 널 문자가 든 긴 비밀번호는 그 앞부분만으로 로그인된다.
		 */
		CONTROL_CHARACTER,
		/** {@link #MAX_BYTES}바이트를 넘는다. */
		TOO_LONG,
		/** 아이디와 같다. */
		SAME_AS_LOGIN_ID

	}

}
