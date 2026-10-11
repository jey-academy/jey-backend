package com.jey.core.member.web;

import com.jey.core.member.GuardianInput;
import com.jey.core.member.domain.Guardian;
import com.jey.core.member.domain.GuardianRelation;
import com.jey.core.shared.PhoneNumber;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "학생에게 붙일 학부모. 같은 전화번호의 학부모가 이미 있으면 새로 만들지 않고 그 학부모를 붙인다")
record GuardianRequest(
		@Schema(description = "같은 전화번호의 학부모가 이미 있으면 쓰이지 않는다(저장된 이름을 바꾸지 않는다)") @NotBlank @Size(
				max = Guardian.MAX_NAME_LENGTH) String name,
		@NotNull GuardianRelation relation,
		@Schema(description = "휴대전화 번호. 하이픈이나 공백이 있어도 된다",
				example = "010-1234-5678") @NotNull PhoneNumber phone) {

	GuardianInput toInput() {
		return new GuardianInput(name, relation, phone);
	}

}
