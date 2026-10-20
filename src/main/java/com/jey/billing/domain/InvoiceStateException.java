package com.jey.billing.domain;

/** 지금 상태의 청구로는 할 수 없는 일이다(결제한 청구를 취소, 취소한 청구에 결제 등). 서비스가 409로 바꾼다. */
public class InvoiceStateException extends RuntimeException {

	private final InvoiceStatus current;

	InvoiceStateException(InvoiceStatus current) {
		super("지금 상태(" + current + ")의 청구로는 할 수 없다");
		this.current = current;
	}

	public InvoiceStatus current() {
		return current;
	}

}
