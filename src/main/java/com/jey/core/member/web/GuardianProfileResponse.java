package com.jey.core.member.web;

import com.jey.core.member.GuardianService.GuardianProfile;
import com.jey.core.shared.PhoneNumber;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "학부모 자신의 정보. 학생과의 관계는 학생 쪽 응답에 있다")
record GuardianProfileResponse(@Schema(requiredMode = RequiredMode.REQUIRED) Long id,
		@Schema(requiredMode = RequiredMode.REQUIRED) String name,
		@Schema(description = "가운데를 가린 전화번호", example = "010-****-5678",
				requiredMode = RequiredMode.REQUIRED) PhoneNumber phone) {

	static GuardianProfileResponse from(GuardianProfile guardian) {
		return new GuardianProfileResponse(guardian.id(), guardian.name(), guardian.phone());
	}

}
