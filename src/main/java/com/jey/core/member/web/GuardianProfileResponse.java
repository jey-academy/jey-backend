package com.jey.core.member.web;

import com.jey.core.member.GuardianService.GuardianProfile;
import com.jey.core.shared.PhoneNumber;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "학부모 자신의 정보. 학생과의 관계는 학생 쪽 응답에 있다")
record GuardianProfileResponse(@Schema(requiredMode = RequiredMode.REQUIRED) Long id,
		@Schema(requiredMode = RequiredMode.REQUIRED) String name,
		@Schema(description = "가운데를 가린 전화번호", example = "010-****-5678",
				requiredMode = RequiredMode.REQUIRED) PhoneNumber phone,
		@Schema(description = "학부모 정보의 버전. 학부모 수정 요청(PUT /guardians/{id})에 그대로 다시 보낸다. 이름·번호가 바뀔 때마다 바뀐다",
				requiredMode = RequiredMode.REQUIRED) Long version) {

	static GuardianProfileResponse from(GuardianProfile guardian) {
		return new GuardianProfileResponse(guardian.id(), guardian.name(), guardian.phone(), guardian.version());
	}

}
