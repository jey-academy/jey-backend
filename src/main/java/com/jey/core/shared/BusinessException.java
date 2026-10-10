package com.jey.core.shared;

import java.util.Objects;

/**
 * 에러 코드를 가진 예외. 각 모듈은 자기 {@link ErrorCode}를 담아 던진다.
 * 응답 변환기는 모듈별 예외 타입을 몰라도 이 타입 하나로 상태, code, detail을 만든다.
 */
public class BusinessException extends RuntimeException {

	private final ErrorCode errorCode;

	public BusinessException(ErrorCode errorCode) {
		this(errorCode, null, null);
	}

	/**
	 * @param detail 사용자에게 그대로 보여주는 문구. 개인정보(전화번호 등)나 내부 식별자를 넣지 않는다.
	 * 비어 있으면 에러 코드의 기본 문구를 쓴다.
	 */
	public BusinessException(ErrorCode errorCode, String detail) {
		this(errorCode, detail, null);
	}

	/**
	 * @param detail 사용자에게 그대로 보여주는 문구. 개인정보(전화번호 등)나 내부 식별자를 넣지 않는다.
	 * 비어 있으면 에러 코드의 기본 문구를 쓴다.
	 * @param cause 원인 예외. 응답에는 나가지 않고 로그에만 남는다.
	 */
	public BusinessException(ErrorCode errorCode, String detail, Throwable cause) {
		super(detailOrDefault(Objects.requireNonNull(errorCode, "errorCode"), detail), cause);
		this.errorCode = errorCode;
	}

	public ErrorCode getErrorCode() {
		return errorCode;
	}

	private static String detailOrDefault(ErrorCode errorCode, String detail) {
		return (detail == null || detail.isBlank()) ? errorCode.message() : detail;
	}

}
