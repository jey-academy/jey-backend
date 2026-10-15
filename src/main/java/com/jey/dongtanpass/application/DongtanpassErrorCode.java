package com.jey.dongtanpass.application;

import com.jey.core.shared.ErrorCode;
import org.springframework.http.HttpStatus;

public enum DongtanpassErrorCode implements ErrorCode {

	// DB의 비교 규칙이 대소문자를 가리지 않아, 대소문자만 다른 이름도 같은 이름이다.
	PARTNER_NAME_DUPLICATED(HttpStatus.CONFLICT, "같은 이름의 연계 학원이 이미 있습니다.");

	private final HttpStatus status;

	private final String message;

	DongtanpassErrorCode(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}

	@Override
	public String code() {
		return "DONGTANPASS_" + name();
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
