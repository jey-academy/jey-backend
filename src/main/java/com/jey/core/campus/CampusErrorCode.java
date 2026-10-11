package com.jey.core.campus;

import com.jey.core.shared.ErrorCode;
import org.springframework.http.HttpStatus;

public enum CampusErrorCode implements ErrorCode {

	// 요청 본문이 가리킨 지점이 없다. 주소가 가리킨 대상이 없는 404와 구분한다.
	INVALID_CAMPUS(HttpStatus.BAD_REQUEST, "없는 지점입니다."),

	CLASS_NOT_FOUND(HttpStatus.NOT_FOUND, "반을 찾을 수 없습니다."),

	CLASS_NAME_DUPLICATED(HttpStatus.CONFLICT, "같은 지점에 같은 이름의 반이 이미 있습니다."),

	INVALID_FEE(HttpStatus.BAD_REQUEST, "수강료는 0원보다 커야 하고, 수강료와 교재비는 1원 단위여야 합니다."),

	DISCOUNT_TYPE_NOT_FOUND(HttpStatus.NOT_FOUND, "할인 유형을 찾을 수 없습니다."),

	DISCOUNT_CODE_DUPLICATED(HttpStatus.CONFLICT, "같은 코드의 할인 유형이 이미 있습니다.");

	private final HttpStatus status;

	private final String message;

	CampusErrorCode(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}

	@Override
	public String code() {
		return "CAMPUS_" + name();
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
