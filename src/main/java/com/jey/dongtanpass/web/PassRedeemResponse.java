package com.jey.dongtanpass.web;

import java.time.Instant;

import com.jey.dongtanpass.application.RedeemedPass;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "사용 처리한 패스. 금액과 청구는 다루지 않는다")
record PassRedeemResponse(
		@Schema(description = "사용 기록 ID", requiredMode = RequiredMode.REQUIRED) long redemptionId,
		@Schema(description = "패스 ID", requiredMode = RequiredMode.REQUIRED) long passId,
		@Schema(description = "학생 ID", requiredMode = RequiredMode.REQUIRED) long studentId,
		@Schema(description = "사용 처리한 시각", requiredMode = RequiredMode.REQUIRED) Instant redeemedAt) {

	static PassRedeemResponse from(RedeemedPass redeemed) {
		return new PassRedeemResponse(redeemed.redemptionId(), redeemed.passId(), redeemed.studentId(),
				redeemed.redeemedAt());
	}

}
