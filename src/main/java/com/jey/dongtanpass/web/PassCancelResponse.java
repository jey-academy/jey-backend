package com.jey.dongtanpass.web;

import com.jey.dongtanpass.application.CanceledPass;
import com.jey.dongtanpass.domain.PassState;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "취소한 패스")
record PassCancelResponse(@Schema(description = "패스 ID", requiredMode = RequiredMode.REQUIRED) Long id,
		@Schema(description = "보이는 상태. 언제나 CANCELED다", requiredMode = RequiredMode.REQUIRED) PassState state) {

	static PassCancelResponse from(CanceledPass pass) {
		return new PassCancelResponse(pass.id(), pass.state());
	}

}
