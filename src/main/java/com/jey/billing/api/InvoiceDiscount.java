package com.jey.billing.api;

import java.math.BigDecimal;

/**
 * 청구 생성 요청에 실어 보내는 할인 1건. 할인 출처(동탄고패스, 쿠폰 등)와 무관한 범용 형태다.
 *
 * @param discountType 할인 유형 코드
 * @param discountRate 할인율 (5%는 0.05)
 * @param discountRef 할인 출처가 붙이는 참조값(예: 패스 번호). billing은 해석하지 않고 저장만 한다.
 */
public record InvoiceDiscount(String discountType, BigDecimal discountRate, String discountRef) {
}
