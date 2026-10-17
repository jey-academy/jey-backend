package com.jey.dongtanpass.web;

import java.util.List;

import com.jey.core.shared.PhoneNumber;
import com.jey.core.shared.RejectUnknownFields;
import com.jey.dongtanpass.domain.StudentNameKey;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// 사람을 가리키는 값이라 주소로 받지 않는다(ADR-0032). 모르는 칸이 있으면 400이다(칸 이름의 오타가 엉뚱한 답으로 이어지지 않게).
// 이름의 길이는 보지 않는다(저장된 어떤 이름보다 길면 맞지 않을 뿐이다).
// @NotBlank는 NBSP·전각 공백을 공백으로 보지 않는다. 비교의 기준(StudentNameKey)으로 한 번 더 걸러, 공백뿐인 이름은 400이다.
@RejectUnknownFields
@Schema(description = "등록 폼에 적은 학생 이름과 학부모 번호")
record HolderCheckRequest(
		@Schema(description = "학생 이름. 띄어쓰기는 달라도 된다") @NotBlank String studentName,
		@Schema(description = "등록 폼의 학부모 휴대전화 번호(전체) 1~4개. 하이픈이나 공백이 있어도 된다. 하나라도 패스를 받을 때 적은 번호와 같으면 맞다")
		@NotNull @Size(min = 1, max = 4) List<@NotNull PhoneNumber> guardianPhones) {

	HolderCheckRequest {
		if (studentName != null) {
			StudentNameKey.of(studentName);
		}
	}

	StudentNameKey studentNameKey() {
		return StudentNameKey.of(studentName);
	}

	// 이름과 번호가 로그에 찍히지 않게 한다.
	@Override
	public String toString() {
		return "HolderCheckRequest[studentName=***, guardianPhones=" + (guardianPhones == null ? null : guardianPhones.size())
				+ "개]";
	}

}
