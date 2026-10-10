package com.jey.core.auth;

import com.jey.core.shared.ErrorCode;
import org.springframework.http.HttpStatus;

public enum AuthErrorCode implements ErrorCode {

	// 아이디가 없는 경우, 비밀번호가 틀린 경우, 비활성 계정을 구분하지 않는다. 응답으로 계정 존재 여부를 알 수 없게 한다.
	INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다.");

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
