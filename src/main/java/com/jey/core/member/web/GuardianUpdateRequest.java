package com.jey.core.member.web;

import com.jey.core.member.domain.Guardian;
import com.jey.core.shared.PhoneNumber;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "학부모 수정. 이 학부모에 연결된 모든 학생에게 반영된다")
record GuardianUpdateRequest(@NotBlank @Size(max = Guardian.MAX_NAME_LENGTH) String name,
		@Schema(description = "새 휴대전화 번호. 보내지 않으면 그대로 둔다. 응답의 가린 번호를 다시 보내면 안 된다. "
				+ "다른 학부모가 쓰는 번호로는 바꿀 수 없다", example = "010-1234-5678") PhoneNumber phone) {
}
