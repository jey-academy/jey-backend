package com.jey.billing.web;

import com.jey.billing.api.PaymentMethod;
import com.jey.core.shared.Money;
import com.jey.core.shared.RejectUnknownFields;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

// 모르는 칸은 400이다(교재비 칸의 오타가 교재비 0원으로 기록되지 않게).
@RejectUnknownFields
@Schema(description = "데스크가 받은 결제. 받은 직후에 보낸다(결제 시각은 서버가 정한다)")
record PaymentRequest(@Schema(description = "결제 수단. CARD_POS 포스기 카드, CASH 현금") @NotNull PaymentMethod method,
		@Schema(description = "받은 수강료(원). 청구 합계와 같아야 한다(다르면 409)") @NotNull Money amount,
		@Schema(description = "받은 교재비(원). 0원 이상 1원 단위, 10,000,000원까지(아니면 400 BILLING_INVALID_BOOK_FEE). 음수·문자열처럼 금액으로 읽을 수 없으면 400(COMMON_VALIDATION_FAILED). 없으면 보내지 않는다(0원)") Money bookFee) {

	Money bookFeeOrZero() {
		return bookFee == null ? Money.ZERO : bookFee;
	}

}
