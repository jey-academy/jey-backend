package com.jey.billing.api;

import java.math.BigDecimal;

/**
 * 다른 모듈이 청구에 실어 보내는 할인 1건. 할인 출처(동탄고 패스, 쿠폰 등)와 무관한 범용 형태다({@link InvoiceIssuing}).
 * 근거가 있는 할인이라 참조값이 꼭 있다. billing은 참조값을 해석하지 않고 청구에 저장만 한다.
 *
 * @param discountType 할인 유형 코드(할인 유형에 있고 켜져 있어야 한다). 30자까지
 * @param discountRate 할인율. 5%는 0.05. 0보다 크고 1보다 작으며 소수 넷째 자리까지
 * @param discountRef 할인 출처가 붙이는 참조값(예: 패스 번호). 100자까지
 * @throws IllegalArgumentException 값이 규칙 밖일 때
 */
public record InvoiceDiscount(String discountType, BigDecimal discountRate, String discountRef) {

	public static final int MAX_TYPE_LENGTH = 30;

	public static final int MAX_REF_LENGTH = 100;

	public InvoiceDiscount {
		if (discountType == null || discountType.isBlank() || discountType.length() > MAX_TYPE_LENGTH) {
			throw new IllegalArgumentException("할인 유형 코드는 비울 수 없고 " + MAX_TYPE_LENGTH + "자까지다");
		}
		if (discountRate == null || discountRate.signum() <= 0 || discountRate.compareTo(BigDecimal.ONE) >= 0
				|| discountRate.stripTrailingZeros().scale() > 4) {
			throw new IllegalArgumentException("할인율은 0보다 크고 1보다 작으며 소수 넷째 자리까지다");
		}
		if (discountRef == null || discountRef.isBlank() || discountRef.length() > MAX_REF_LENGTH) {
			throw new IllegalArgumentException("참조값은 비울 수 없고 " + MAX_REF_LENGTH + "자까지다");
		}
	}

}
