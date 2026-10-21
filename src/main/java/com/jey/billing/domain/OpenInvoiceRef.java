package com.jey.billing.domain;

/** 열린 청구의 ID와 그 청구가 속한 지점(만들 때의 학생 지점). */
public record OpenInvoiceRef(long id, long campusId) {
}
