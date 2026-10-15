package com.jey.dongtanpass.application;

import com.jey.core.shared.ErrorCode;
import org.springframework.http.HttpStatus;

public enum DongtanpassErrorCode implements ErrorCode {

	PARTNER_NOT_FOUND(HttpStatus.NOT_FOUND, "연계 학원을 찾을 수 없습니다."),

	// 관리자가 꺼진 학원에 계정을 만들려 했다. 꺼진 학원의 계정이 패스 기능을 쓰려 한 것(403)과 구분한다.
	PARTNER_INACTIVE_FOR_ACCOUNT(HttpStatus.CONFLICT, "꺼진 연계 학원에는 계정을 만들 수 없습니다. 학원을 먼저 켜 주세요."),

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
