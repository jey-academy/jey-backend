package com.jey.core.audit;

import com.jey.core.shared.ErrorCode;
import org.springframework.http.HttpStatus;

public enum AuditErrorCode implements ErrorCode {

	INVALID_PERIOD(HttpStatus.BAD_REQUEST, "조회 기간의 시작이 끝보다 늦습니다."),

	// 대상 ID만 주면 종류가 다른 대상(학생 5번과 학부모 5번)의 기록이 섞여 나온다.
	TARGET_TYPE_REQUIRED(HttpStatus.BAD_REQUEST, "대상 ID로 찾으려면 대상의 종류도 같이 주어야 합니다.");

	private final HttpStatus status;

	private final String message;

	AuditErrorCode(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}

	@Override
	public String code() {
		return "AUDIT_" + name();
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
