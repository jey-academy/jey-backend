package com.jey.dongtanpass.web;

import com.jey.core.shared.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "받을 금액 미리 보기")
record PartnerQuoteRequest(@Schema(description = "원래 수강료(원, 첫 달 한 달 치). 0보다 크고 1원 단위, 10,000,000원까지. 아니면 400(DONGTANPASS_INVALID_ORIGINAL_FEE). 음수·문자열처럼 금액으로 읽을 수 없으면 400(COMMON_VALIDATION_FAILED)")
		@NotNull Money originalFee) {
}
