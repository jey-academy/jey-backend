package com.jey.core.auth;

import com.jey.core.shared.ErrorCode;
import org.springframework.http.HttpStatus;

public enum AuthErrorCode implements ErrorCode {

	// 아이디가 없는 경우, 비밀번호가 틀린 경우, 비활성 계정을 구분하지 않는다. 응답으로 계정 존재 여부를 알 수 없게 한다.
	INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다."),

	// 로그인 시도 한도를 넘었다. 아이디가 있는지와 무관하게 같은 횟수에서 난다. 응답의 Retry-After 헤더에 남은 시간(초)이 있다.
	TOO_MANY_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS, "로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요."),

	// 아래는 계정 관리(ADR-0030). 관리자만 받는 응답이라 아이디가 이미 있다는 것을 알려 줘도 된다.
	LOGIN_ID_DUPLICATED(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다."),

	CAMPUS_NOT_FOUND(HttpStatus.BAD_REQUEST, "없는 지점입니다."),

	ROLE_CAMPUS_MISMATCH(HttpStatus.BAD_REQUEST, "직원은 소속 지점이 있어야 하고, 관리자는 소속 지점을 가질 수 없습니다."),

	// 연계 학원 계정은 연계 학원과 묶여 있다. 계정 관리에서는 만들지 않고, 그 역할로 바꾸거나 그 역할에서 바꾸지도 않는다.
	PARTNER_ACCOUNT_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, "연계 학원 계정은 여기서 만들거나 역할을 바꿀 수 없습니다."),

	// 길이(10자 이상, 72바이트 이하)를 어겼거나, 아이디와 같거나, 쓸 수 없는 글자가 있다. 어느 것인지는 가리지 않는다.
	PASSWORD_NOT_ACCEPTABLE(HttpStatus.BAD_REQUEST,
			"비밀번호는 10자 이상 72자 이하(한글은 24자까지)이고 아이디와 달라야 합니다. 줄바꿈 같은 제어 문자는 쓸 수 없습니다."),

	// 자기 자신을 차단하거나, 자기 역할·지점을 바꾸거나, 자기 비밀번호를 "다시 정하기"로 바꾸려 했다.
	SELF_CHANGE_NOT_ALLOWED(HttpStatus.CONFLICT, "자기 계정에는 할 수 없는 변경입니다."),

	LAST_ADMIN(HttpStatus.CONFLICT, "마지막 남은 관리자는 차단하거나 다른 역할로 바꿀 수 없습니다."),

	CURRENT_PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST, "지금 비밀번호가 올바르지 않습니다."),

	PASSWORD_UNCHANGED(HttpStatus.BAD_REQUEST, "새 비밀번호가 지금 비밀번호와 같습니다."),

	// 지금 비밀번호를 틀린 횟수가 한도를 넘었다. 응답의 Retry-After 헤더에 남은 시간(초)이 있다.
	TOO_MANY_PASSWORD_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS, "비밀번호 변경 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.");

	private final HttpStatus status;

	private final String message;

	AuthErrorCode(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}

	@Override
	public String code() {
		return "AUTH_" + name();
	}

	@Override
	public HttpStatus status() {
		return status;
	}

	@Override
	public String message() {
		return message;
	}

}
