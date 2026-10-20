package com.jey.billing.domain;

/** 받은 수강료가 청구 합계와 다르다(나눠 내기·일부 결제는 받지 않는다). 서비스가 409로 바꾼다. */
public class PaymentAmountMismatchException extends RuntimeException {

	PaymentAmountMismatchException() {
		super("수강료는 청구 합계와 같아야 한다");
	}

}
