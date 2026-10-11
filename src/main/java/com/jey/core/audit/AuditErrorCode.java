package com.jey.core.audit;

import com.jey.core.shared.ErrorCode;
import org.springframework.http.HttpStatus;

public enum AuditErrorCode implements ErrorCode {

	INVALID_PERIOD(HttpStatus.BAD_REQUEST, "조회 기간의 시작이 끝보다 늦습니다.");

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
