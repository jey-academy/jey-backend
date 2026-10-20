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

	// 근거(참조값)가 필요한 할인(예: 동탄고 패스)은 직원이 고르지 않는다. 그 할인을 준 곳(패스 사용 기록)에서 청구를 만든다.
	DISCOUNT_NEEDS_REFERENCE(HttpStatus.BAD_REQUEST, "이 할인은 직접 고를 수 없습니다. 패스 사용 기록에서 청구를 만들어 주세요."),

	// 한 학생 한 달에 열린 청구는 하나다.
	INVOICE_ALREADY_EXISTS(HttpStatus.CONFLICT, "이 학생의 그 달 청구가 이미 있습니다."),

	INVOICE_NOT_FOUND(HttpStatus.NOT_FOUND, "청구를 찾을 수 없습니다."),

	// 결제 전(ISSUED) 청구에만 결제한다. 이미 결제했거나 취소한 청구다.
	INVOICE_NOT_PAYABLE(HttpStatus.CONFLICT, "결제할 수 없는 청구입니다. 이미 결제했거나 취소한 청구입니다."),

	// 결제 전(ISSUED) 청구만 취소한다. 결제한 청구는 결제를 먼저 취소한다.
	INVOICE_NOT_CANCELABLE(HttpStatus.CONFLICT, "취소할 수 없는 청구입니다. 결제한 청구는 결제를 먼저 취소해 주세요."),

	// 수강료는 청구 합계 그대로 받는다(나눠 내기·일부 결제 없음).
	PAYMENT_AMOUNT_MISMATCH(HttpStatus.CONFLICT, "받은 수강료가 청구 금액과 다릅니다."),

	// 청구를 잠그고 상태를 보므로 보통은 INVOICE_NOT_PAYABLE이 먼저 나간다. 유니크 키가 막은 마지막 방어선이다.
	PAYMENT_ALREADY_EXISTS(HttpStatus.CONFLICT, "이 청구의 결제가 이미 있습니다."),

	PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "결제를 찾을 수 없습니다."),

	PAYMENT_ALREADY_CANCELED(HttpStatus.CONFLICT, "이미 취소한 결제입니다."),

	INVALID_PERIOD(HttpStatus.BAD_REQUEST, "기간이 올바르지 않습니다. 시작일은 종료일보다 뒤일 수 없고, 2000-01-01부터 2099-12-31 사이여야 합니다."),

	PERIOD_TOO_LONG(HttpStatus.BAD_REQUEST, "기간은 1년까지 볼 수 있습니다.");

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
