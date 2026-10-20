package com.jey.billing.api;

import com.jey.core.shared.Money;

/** 다른 모듈이 만든 청구. 상세는 청구 API로 본다. */
public record IssuedInvoice(long invoiceId, Money totalAmount) {
}
