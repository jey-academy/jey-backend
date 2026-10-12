package com.jey.core.shared;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * 모듈과 무관한 공통 에러 코드.
 * {@code VALIDATION_FAILED}는 필드별 오류 목록이 붙는 검증 실패, {@code BAD_REQUEST}는 그 밖의 요청 형식 오류다.
 */
public enum CommonErrorCode implements ErrorCode {

	VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
	BAD_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다."),
	UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
	FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다."),
	NOT_FOUND(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다."),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다."),
	CONFLICT(HttpStatus.CONFLICT, "현재 상태에서는 처리할 수 없습니다."),

	// Idempotency-Key (ADR-0027). 키가 없거나 형식이 틀리다.
	IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "Idempotency-Key 헤더가 필요합니다."),

	// 같은 키로 다른 내용의 요청이 이미 왔다. 새 요청에는 새 키를 쓴다.
	IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_ENTITY, "이미 다른 요청에 쓴 Idempotency-Key입니다."),

	// 같은 키의 요청이 아직 끝나지 않았다. 잠시 뒤 같은 키로 다시 보내면 그 결과를 받는다.
	IDEMPOTENCY_IN_PROGRESS(HttpStatus.CONFLICT, "같은 요청을 처리하고 있습니다. 잠시 후 다시 시도해 주세요."),

	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.");

	private final HttpStatus status;

	private final String message;

	CommonErrorCode(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}

	/**
	 * Spring MVC 표준 예외처럼 에러 코드 없이 HTTP 상태만 있는 경우에 쓴다.
	 * 매핑이 없는 4xx는 {@code BAD_REQUEST}, 그 외는 {@code INTERNAL_ERROR}로 묶으므로 반환값의 {@code status()}가 입력과 다를 수 있다.
	 */
	public static CommonErrorCode fromStatus(HttpStatusCode status) {
		return switch (status.value()) {
			case 401 -> UNAUTHENTICATED;
			case 403 -> FORBIDDEN;
			case 404 -> NOT_FOUND;
			case 405 -> METHOD_NOT_ALLOWED;
			case 409 -> CONFLICT;
			default -> status.is4xxClientError() ? BAD_REQUEST : INTERNAL_ERROR;
		};
	}

	@Override
	public String code() {
		return "COMMON_" + name();
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
