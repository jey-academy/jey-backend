package com.jey.core.member;

import com.jey.core.shared.ErrorCode;
import org.springframework.http.HttpStatus;

public enum MemberErrorCode implements ErrorCode {

	STUDENT_NOT_FOUND(HttpStatus.NOT_FOUND, "학생을 찾을 수 없습니다."),

	// 없는 학부모이거나, 그 학생에게 연결되지 않은 학부모다.
	GUARDIAN_NOT_FOUND(HttpStatus.NOT_FOUND, "학부모를 찾을 수 없습니다."),

	// 요청 본문이 가리킨 지점이 없다. 주소가 가리킨 대상이 없는 404와 구분한다.
	INVALID_CAMPUS(HttpStatus.BAD_REQUEST, "없는 지점입니다."),

	INVALID_CLASS(HttpStatus.BAD_REQUEST, "고를 수 없는 반입니다. 학생의 지점에서 운영 중인 반이어야 합니다."),

	CONTACT_REQUIRED(HttpStatus.BAD_REQUEST, "학부모나 학생 본인의 전화번호가 하나는 있어야 합니다."),

	GUARDIAN_PHONE_REPEATED(HttpStatus.BAD_REQUEST, "같은 전화번호의 학부모를 두 번 적었습니다."),

	STUDENT_DUPLICATED(HttpStatus.CONFLICT, "같은 연락처에 같은 이름의 학생이 이미 있습니다."),

	GUARDIAN_PHONE_DUPLICATED(HttpStatus.CONFLICT, "다른 학부모가 쓰고 있는 전화번호입니다."),

	GUARDIAN_ALREADY_LINKED(HttpStatus.CONFLICT, "이미 이 학생에게 연결된 학부모입니다."),

	LAST_CONTACT(HttpStatus.CONFLICT, "마지막 연락처는 지울 수 없습니다. 다른 연락처를 먼저 넣어 주세요.");

	private final HttpStatus status;

	private final String message;

	MemberErrorCode(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}

	@Override
	public String code() {
		return "MEMBER_" + name();
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
