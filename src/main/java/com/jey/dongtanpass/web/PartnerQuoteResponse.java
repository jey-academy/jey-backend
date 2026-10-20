package com.jey.dongtanpass.web;

import java.math.BigDecimal;

import com.jey.core.shared.Money;
import com.jey.dongtanpass.domain.PartnerDiscount;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "받을 금액. 받을 금액 = 원래 수강료 × (1 − 할인율)에서 1원 미만을 버린 값, 할인액 = 원래 수강료 − 받을 금액")
record PartnerQuoteResponse(@Schema(description = "원래 수강료(원)", requiredMode = RequiredMode.REQUIRED) Money originalFee,
		@Schema(description = "이 패스의 할인율(발급 때 값). 5%는 0.05", requiredMode = RequiredMode.REQUIRED) BigDecimal discountRate,
		@Schema(description = "할인액(원)", requiredMode = RequiredMode.REQUIRED) Money discountAmount,
		@Schema(description = "받을 금액(원). 학원은 이 금액으로 결제받는다", requiredMode = RequiredMode.REQUIRED) Money payableAmount) {

	static PartnerQuoteResponse from(PartnerDiscount discount) {
		return new PartnerQuoteResponse(discount.originalFee(), discount.discountRate(), discount.discountAmount(),
				discount.payableAmount());
	}

}
