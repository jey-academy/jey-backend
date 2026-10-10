package com.jey.core.shared;

import org.springframework.http.HttpStatus;

/**
 * API 에러 코드 규약. 각 모듈은 자기 enum으로 구현한다(코드 값 예: {@code DONGTANPASS_PASS_EXPIRED}).
 * 공통 코드는 {@link CommonErrorCode}.
 */
public interface ErrorCode {

	/** 클라이언트가 분기에 쓰는 값. {@code {모듈}_{사유}}, 대문자 스네이크. 프론트와의 계약이라 한 번 내보낸 값은 바꾸지 않는다. */
	String code();

	HttpStatus status();

	/** 사용자에게 보여줄 기본 안내 문구. 예외에 문구를 따로 주지 않으면 응답의 detail로 나간다. */
	String message();

}
