package com.jey.dongtanpass.web;

import java.time.Instant;

import com.jey.core.auth.api.PartnerAccount;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "만든 연계 학원 계정. 비밀번호는 어떤 응답에도 없다")
record PartnerAccountResponse(@Schema(description = "계정 ID. 계정 관리(/api/v1/accounts/{id})에서 쓴다",
		requiredMode = RequiredMode.REQUIRED) Long id,
		@Schema(description = "저장한 로그인 아이디(소문자로 맞춘 것)", requiredMode = RequiredMode.REQUIRED) String loginId,
		@Schema(description = "화면에 보일 이름", requiredMode = RequiredMode.REQUIRED) String name,
		@Schema(description = "소속 연계 학원", requiredMode = RequiredMode.REQUIRED) Long partnerId,
		@Schema(description = "계정을 만든 시각", requiredMode = RequiredMode.REQUIRED) Instant createdAt) {

	static PartnerAccountResponse from(PartnerAccount account) {
		return new PartnerAccountResponse(account.id(), account.loginId(), account.name(), account.partnerId(),
				account.createdAt());
	}

}
