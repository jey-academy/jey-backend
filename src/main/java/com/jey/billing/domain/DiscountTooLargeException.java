package com.jey.billing.domain;

/** 더한 할인율이 100% 이상이다(0원 이하의 청구가 된다). */
public class DiscountTooLargeException extends RuntimeException {

	DiscountTooLargeException() {
		super("더한 할인율이 100% 이상이다");
	}

}
