package com.jey.dongtanpass.web;

import java.math.BigDecimal;
import java.time.Instant;

import com.jey.core.shared.Money;
import com.jey.dongtanpass.application.PartnerRedeemedPass;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "연계 학원이 쓴 JEY 발급 패스. 학원은 payableAmount로 결제받는다")
record PartnerRedeemResponse(
		@Schema(description = "연계 학원 사용 기록의 ID", requiredMode = RequiredMode.REQUIRED) long redemptionId,
		@Schema(description = "패스 ID", requiredMode = RequiredMode.REQUIRED) long passId,
		@Schema(description = "원래 수강료(원)", requiredMode = RequiredMode.REQUIRED) Money originalFee,
		@Schema(description = "이 패스의 할인율(발급 때 값). 5%는 0.05", requiredMode = RequiredMode.REQUIRED) BigDecimal discountRate,
		@Schema(description = "할인액(원) = 원래 수강료 − 받을 금액", requiredMode = RequiredMode.REQUIRED) Money discountAmount,
		@Schema(description = "받을 금액(원). 원래 수강료 × (1 − 할인율)에서 1원 미만을 버린 값", requiredMode = RequiredMode.REQUIRED) Money payableAmount,
		@Schema(description = "사용 처리한 시각", requiredMode = RequiredMode.REQUIRED) Instant redeemedAt) {

	static PartnerRedeemResponse from(PartnerRedeemedPass pass) {
		return new PartnerRedeemResponse(pass.redemptionId(), pass.passId(), pass.discount().originalFee(),
				pass.discount().discountRate(), pass.discount().discountAmount(), pass.discount().payableAmount(),
				pass.redeemedAt());
	}

}
