package com.jey.billing.web;

import java.time.Instant;

import com.jey.billing.api.PaymentMethod;
import com.jey.billing.application.PaymentView;
import com.jey.billing.domain.PaymentStatus;
import com.jey.core.shared.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "결제 하나. 금액은 원 단위 숫자")
record PaymentResponse(@Schema(description = "결제 ID", requiredMode = RequiredMode.REQUIRED) long paymentId,
		@Schema(description = "청구 ID", requiredMode = RequiredMode.REQUIRED) long invoiceId,
		@Schema(description = "지점(청구의 지점)", requiredMode = RequiredMode.REQUIRED) long campusId,
		@Schema(description = "학생 ID", requiredMode = RequiredMode.REQUIRED) long studentId,
		@Schema(description = "결제 수단. CARD_POS 포스기 카드, CASH 현금", requiredMode = RequiredMode.REQUIRED) PaymentMethod method,
		@Schema(description = "받은 수강료(원)", requiredMode = RequiredMode.REQUIRED) Money amount,
		@Schema(description = "받은 교재비(원)", requiredMode = RequiredMode.REQUIRED) Money bookFee,
		@Schema(description = "상태. COMPLETED 받음, CANCELED 취소함(환불은 데스크에서 따로)", requiredMode = RequiredMode.REQUIRED) PaymentStatus status,
		@Schema(description = "결제를 기록한 시각", requiredMode = RequiredMode.REQUIRED) Instant paidAt,
		@Schema(description = "취소한 시각. 취소하지 않았으면 null", requiredMode = RequiredMode.REQUIRED,
				nullable = true) Instant canceledAt) {

	static PaymentResponse from(PaymentView payment) {
		return new PaymentResponse(payment.paymentId(), payment.invoiceId(), payment.campusId(), payment.studentId(),
				payment.method(), payment.amount(), payment.bookFee(), payment.status(), payment.paidAt(),
				payment.canceledAt());
	}

}
