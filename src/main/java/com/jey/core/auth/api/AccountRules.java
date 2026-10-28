package com.jey.core.auth.api;

/**
 * 계정의 아이디와 이름 규칙. 계정을 만드는 요청을 받는 쪽(계정 관리, 제이G패스의 연계 학원 계정)이 같은 값으로 검증한다.
 * 저장하는 쪽({@code User})도 이 값을 쓴다. 두 곳에 따로 적으면 한쪽만 바뀌었을 때 검증을 통과한 값이 저장에서 예외가 된다.
 */
public final class AccountRules {

	/** 영문 소문자, 숫자, 점, 밑줄, 하이픈만. 3~50자, 첫 글자는 영문이나 숫자. */
	public static final String LOGIN_ID_PATTERN = "[a-z0-9][a-z0-9._-]{2,49}";

	public static final int MAX_NAME_LENGTH = 50;

	/** 요청에서 받는 비밀번호의 길이 상한. 실제 규칙(10자 이상, 72바이트 이하)은 서버의 비밀번호 규칙이 본다. */
	public static final int MAX_PASSWORD_INPUT_LENGTH = 100;

	private AccountRules() {
	}

}
