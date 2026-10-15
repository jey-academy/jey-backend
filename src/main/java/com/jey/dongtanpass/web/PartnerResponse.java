package com.jey.dongtanpass.web;

import java.time.Instant;

import com.jey.dongtanpass.application.PartnerView;
import com.jey.dongtanpass.domain.PartnerType;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "연계 학원 하나")
record PartnerResponse(@Schema(requiredMode = RequiredMode.REQUIRED) Long id,
		@Schema(description = "학원 이름", requiredMode = RequiredMode.REQUIRED) String name,
		@Schema(description = "종류. 지금은 ACADEMY뿐이다", requiredMode = RequiredMode.REQUIRED) PartnerType type,
		@Schema(description = "false면 꺼진 학원이다. 새 패스를 발급할 수 없고 그 학원의 계정은 패스 기능을 쓸 수 없다",
				requiredMode = RequiredMode.REQUIRED) boolean active,
		@Schema(description = "만든 시각", requiredMode = RequiredMode.REQUIRED) Instant createdAt) {

	static PartnerResponse from(PartnerView partner) {
		return new PartnerResponse(partner.id(), partner.name(), partner.type(), partner.active(),
				partner.createdAt());
	}

}
