package com.jey.core.auth;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 비밀번호로 받을 수 있는 값의 규칙 (ADR-0030). 계정을 만들 때, 비밀번호를 바꿀 때, 첫 관리자를 만들 때 모두 이것 하나를 쓴다.
 *
 * <p>길이만 본다. 글자 종류(대문자, 특수문자)를 강제하지 않는다. 비밀번호 자체는 여기에 남기지 않는다(무엇을 어겼는지만 돌려준다).
 */
public final class PasswordPolicy {

	public static final int MIN_LENGTH = 10;

	/** BCrypt는 72바이트까지만 읽는다. 넘는 부분은 조용히 버려지므로 넘으면 받지 않는다. 한글은 한 글자가 3바이트다. */
	public static final int MAX_BYTES = 72;

	private PasswordPolicy() {
	}

	/**
	 * 길이 규칙만 본다. 요청을 받는 자리(검증)에서 쓴다.
	 *
	 * @return 어긴 규칙. 받을 수 있는 값이면 비어 있다
	 */
	public static Optional<Violation> checkLength(String password) {
		if (password == null || password.isBlank() || password.length() < MIN_LENGTH) {
			return Optional.of(Violation.TOO_SHORT);
		}
		if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
			return Optional.of(Violation.TOO_LONG);
		}
		return Optional.empty();
	}

	/**
	 * 길이 규칙과, 아이디를 그대로 비밀번호로 쓰지 않았는지를 본다.
	 *
	 * @param loginId 그 계정의 아이디. 대소문자와 앞뒤 공백은 가리지 않고 비교한다
	 * @return 어긴 규칙. 받을 수 있는 값이면 비어 있다
	 */
	public static Optional<Violation> check(String password, String loginId) {
		Optional<Violation> length = checkLength(password);
		if (length.isPresent()) {
			return length;
		}
		if (loginId != null && password.trim().equalsIgnoreCase(loginId.trim())) {
			return Optional.of(Violation.SAME_AS_LOGIN_ID);
		}
		return Optional.empty();
	}

	public enum Violation {

		/** 비었거나 {@link #MIN_LENGTH}자보다 짧다. */
		TOO_SHORT,
		/** {@link #MAX_BYTES}바이트를 넘는다. */
		TOO_LONG,
		/** 아이디와 같다. */
		SAME_AS_LOGIN_ID

	}

}
