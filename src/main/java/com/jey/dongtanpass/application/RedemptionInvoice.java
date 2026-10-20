package com.jey.dongtanpass.application;

import com.jey.core.shared.Money;

/** 패스 사용 기록에서 만든 청구. 상세는 수납 모듈의 청구 API로 본다. */
public record RedemptionInvoice(long redemptionId, long invoiceId, Money totalAmount) {
}
