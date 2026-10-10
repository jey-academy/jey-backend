package com.jey.core.auth;

import com.jey.core.shared.ErrorCode;
import org.springframework.http.HttpStatus;

public enum AuthErrorCode implements ErrorCode {

	// 아이디가 없는 경우, 비밀번호가 틀린 경우, 비활성 계정을 구분하지 않는다. 응답으로 계정 존재 여부를 알 수 없게 한다.
	INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다."),

	// 로그인 시도 한도를 넘었다. 아이디가 있는지와 무관하게 같은 횟수에서 난다. 응답의 Retry-After 헤더에 남은 시간(초)이 있다.
	TOO_MANY_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS, "로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.");

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
