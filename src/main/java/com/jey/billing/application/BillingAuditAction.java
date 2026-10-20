package com.jey.billing.application;

import com.jey.core.audit.api.AuditAction;
import com.jey.core.audit.api.AuditOutcome;

/**
 * 수납 모듈에서 감사 로그에 남기는 행위. 이름은 이미 쌓인 기록에 적혀 있고 조회 조건으로 쓰이므로 바꾸지 않는다.
 */
enum BillingAuditAction implements AuditAction {

	/** 청구를 만들었다. 대상은 그 학생, 덧붙이는 값은 청구 ID·대상 월·합계. */
	INVOICE_CREATED("billing.invoice-created", AuditOutcome.DONE),

	/** 결제 전 청구를 취소했다. 대상은 그 학생, 덧붙이는 값은 청구 ID. 결제·결제 취소는 이벤트로 남는다. */
	INVOICE_CANCELED("billing.invoice-canceled", AuditOutcome.DONE);

	private final String code;

	private final AuditOutcome outcome;

	BillingAuditAction(String code, AuditOutcome outcome) {
		this.code = code;
		this.outcome = outcome;
	}

	@Override
	public String code() {
		return code;
	}

	@Override
	public AuditOutcome outcome() {
		return outcome;
	}

}
