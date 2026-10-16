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

	/**
	 * 요청의 내용이 아니라 부르는 쪽의 지금 상태 때문에 잠시 막은 거부인지(예: 꺼진 연계 학원). 그 상태가 풀리면 같은 요청이 성공할 수 있다.
	 * 이런 거부는 멱등 키의 응답으로 보관하지 않는다(ADR-0027). 보관하면 상태가 풀린 뒤에도 같은 키로는 보관 기간 내내 같은 거부를 받는다.
	 * 업무 규칙의 거부(이미 사용됨, 이미 취소됨)는 같은 요청을 다시 해도 바뀌지 않으므로 {@code false}다.
	 */
	default boolean temporaryRejection() {
		return false;
	}

}
