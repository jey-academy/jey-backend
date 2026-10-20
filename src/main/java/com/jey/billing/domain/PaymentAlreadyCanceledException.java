package com.jey.billing.domain;

/** 이미 취소한 결제다. 서비스가 409로 바꾼다. */
public class PaymentAlreadyCanceledException extends RuntimeException {

	PaymentAlreadyCanceledException() {
		super("이미 취소한 결제다");
	}

}
