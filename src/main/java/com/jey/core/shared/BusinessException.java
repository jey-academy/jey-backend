package com.jey.core.shared;

/**
 * 업무 규칙 위반을 나타내는 예외의 기반 클래스. 응답 변환기는 이 타입만 안다.
 */
public class BusinessException extends RuntimeException {

	private final ErrorCode errorCode;

	public BusinessException(ErrorCode errorCode) {
		this(errorCode, errorCode.message());
	}

	public BusinessException(ErrorCode errorCode, String detail) {
		super(detail);
		this.errorCode = errorCode;
	}

	public ErrorCode getErrorCode() {
		return errorCode;
	}

}
