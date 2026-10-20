package com.jey.billing.domain;

/** 할인을 하나만 받는 설정({@link DiscountPolicy#SINGLE})에 할인을 둘 이상 골랐다. */
public class TooManyDiscountsException extends RuntimeException {

	TooManyDiscountsException() {
		super("이 설정에서는 할인을 하나만 받는다");
	}

}
