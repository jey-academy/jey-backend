package com.jey.core.member.web;

import com.jey.core.member.StudentView.GuardianView;
import com.jey.core.member.domain.GuardianRelation;
import com.jey.core.shared.PhoneNumber;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "학생에게 연결된 학부모")
record GuardianResponse(@Schema(requiredMode = RequiredMode.REQUIRED) Long id,
		@Schema(requiredMode = RequiredMode.REQUIRED) String name,
		@Schema(description = "이 학생에게 누구인지. 같은 학부모가 학생마다 다른 관계일 수 있다",
				requiredMode = RequiredMode.REQUIRED) GuardianRelation relation,
		@Schema(description = "가운데를 가린 전화번호", example = "010-****-5678",
				requiredMode = RequiredMode.REQUIRED) PhoneNumber phone,
		@Schema(description = "등록·학부모 추가 응답에서, 새로 만들지 않고 같은 전화번호의 기존 학부모에 연결했으면 true. "
				+ "이때 name은 입력한 이름이 아니라 저장돼 있던 이름이다. 조회 응답에서는 항상 false",
				requiredMode = RequiredMode.REQUIRED) boolean linkedToExisting) {

	static GuardianResponse from(GuardianView guardian) {
		return new GuardianResponse(guardian.id(), guardian.name(), guardian.relation(), guardian.phone(),
				guardian.linkedToExisting());
	}

}
