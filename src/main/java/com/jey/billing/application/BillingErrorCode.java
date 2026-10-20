package com.jey.billing.application;

import com.jey.core.shared.ErrorCode;
import org.springframework.http.HttpStatus;

public enum BillingErrorCode implements ErrorCode {

	STUDENT_NOT_FOUND(HttpStatus.NOT_FOUND, "학생을 찾을 수 없습니다."),

	// 퇴원 등 재원 중이 아닌 학생에게는 새 청구를 만들지 않는다.
	STUDENT_NOT_ENROLLED(HttpStatus.CONFLICT, "재원 중인 학생이 아닙니다."),

	CLASS_NOT_FOUND(HttpStatus.NOT_FOUND, "반을 찾을 수 없습니다."),

	// 꺼진 반으로는 새 청구를 만들지 않는다(이미 만든 청구는 그대로다).
	CLASS_INACTIVE(HttpStatus.CONFLICT, "운영하지 않는 반입니다."),

	// 청구는 학생의 지점에 속한다. 다른 지점의 반은 그 청구에 넣지 않는다.
	CLASS_NOT_AT_STUDENT_CAMPUS(HttpStatus.CONFLICT, "학생의 지점에 있는 반이 아닙니다."),

	DISCOUNT_TYPE_NOT_FOUND(HttpStatus.NOT_FOUND, "할인 유형을 찾을 수 없습니다."),

	DISCOUNT_TYPE_INACTIVE(HttpStatus.CONFLICT, "쓰지 않는 할인 유형입니다."),

	// 할인을 하나만 받는 설정이다. 어느 할인을 남길지는 직원이 고른다.
	TOO_MANY_DISCOUNTS(HttpStatus.BAD_REQUEST, "지금 설정에서는 할인을 하나만 고를 수 있습니다."),

	// 할인율을 더하면 100% 이상이다(0원 이하의 청구가 된다).
	DISCOUNT_TOO_LARGE(HttpStatus.BAD_REQUEST, "할인율의 합이 100% 이상입니다."),

	// 한 학생 한 달에 열린 청구는 하나다.
	INVOICE_ALREADY_EXISTS(HttpStatus.CONFLICT, "이 학생의 그 달 청구가 이미 있습니다."),

	INVOICE_NOT_FOUND(HttpStatus.NOT_FOUND, "청구를 찾을 수 없습니다.");

	private final HttpStatus status;

	private final String message;

	BillingErrorCode(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}

	@Override
	public String code() {
		return "BILLING_" + name();
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
