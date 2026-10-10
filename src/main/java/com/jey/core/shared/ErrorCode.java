package com.jey.core.shared;

import org.springframework.http.HttpStatus;

/**
 * API 에러 코드 규약. 각 모듈은 자기 enum으로 구현한다(예: gopass의 {@code GOPASS_PASS_EXPIRED}).
 */
public interface ErrorCode {

	/** 클라이언트가 분기에 쓰는 값. {@code {모듈}_{사유}}, 대문자 스네이크. */
	String code();

	HttpStatus status();

	/** 사용자에게 보여줄 기본 안내 문구. */
	String message();

}
