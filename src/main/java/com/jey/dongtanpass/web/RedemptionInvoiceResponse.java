package com.jey.dongtanpass.web;

import com.jey.core.shared.Money;
import com.jey.dongtanpass.application.RedemptionInvoice;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "패스 사용 기록에서 만든 청구. 상세(라인·할인·결제)는 GET /api/v1/billing/invoices/{invoiceId}")
record RedemptionInvoiceResponse(@Schema(description = "사용 기록 ID", requiredMode = RequiredMode.REQUIRED) long redemptionId,
		@Schema(description = "만든 청구 ID. 결제는 POST /api/v1/billing/invoices/{invoiceId}/payments", requiredMode = RequiredMode.REQUIRED) long invoiceId,
		@Schema(description = "청구 합계(원)", requiredMode = RequiredMode.REQUIRED) Money totalAmount) {

	static RedemptionInvoiceResponse from(RedemptionInvoice invoice) {
		return new RedemptionInvoiceResponse(invoice.redemptionId(), invoice.invoiceId(), invoice.totalAmount());
	}

}
