package com.jey.dongtanpass.web;

import com.jey.core.shared.PhoneNumber;
import com.jey.core.shared.RejectUnknownFields;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// 사람을 가리키는 값이라 주소로 받지 않는다(ADR-0032). 모르는 칸이 있으면 400이다(칸 이름의 오타가 엉뚱한 응답으로 이어지지 않게).
// 이름의 길이는 보지 않는다. 저장된 어떤 이름보다 긴 이름도 다른 틀린 이름과 같은 404다.
@RejectUnknownFields
@Schema(description = "링크를 다시 받을 학생 이름과 학부모 번호")
record HolderRelinkRequest(
		@Schema(description = "학생 이름. 띄어쓰기는 달라도 된다") @NotBlank String studentName,
		@Schema(description = "패스를 받을 때 적은 학부모 휴대전화 번호(전체). 하이픈이나 공백이 있어도 된다",
				example = "010-1234-5678") @NotNull PhoneNumber guardianPhone) {

	// 이름과 번호가 로그에 찍히지 않게 한다.
	@Override
	public String toString() {
		return "HolderRelinkRequest[studentName=***, guardianPhone=***]";
	}

}
