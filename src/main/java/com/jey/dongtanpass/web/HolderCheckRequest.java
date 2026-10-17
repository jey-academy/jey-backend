package com.jey.dongtanpass.web;

import java.util.List;

import com.jey.core.shared.PhoneNumber;
import com.jey.core.shared.RejectUnknownFields;
import com.jey.dongtanpass.domain.StudentNameKey;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// 사람을 가리키는 값이라 주소로 받지 않는다(ADR-0032). 모르는 칸이 있으면 400이다(칸 이름의 오타가 엉뚱한 답으로 이어지지 않게).
// 이름의 길이는 보지 않는다(저장된 어떤 이름보다 길면 맞지 않을 뿐이다).
// 비었거나 공백(NBSP·전각 포함)뿐인 이름은 그 칸의 400이다(@StudentNameText, 공백의 기준은 이름 비교와 같다).
// 번호는 4개까지다. 학생 한 명의 학부모 상한(core.member의 StudentGuardian.MAX_PER_STUDENT)과 같은 값이다.
@RejectUnknownFields
@Schema(description = "등록 폼에 적은 학생 이름과 학부모 번호")
record HolderCheckRequest(
		@Schema(description = "학생 이름. 띄어쓰기는 달라도 된다", minLength = 1) @NotNull @StudentNameText String studentName,
		@Schema(description = "등록 폼의 학부모 휴대전화 번호(전체) 1~" + MAX_PHONES
				+ "개. 하이픈이나 공백이 있어도 된다. 하나라도 패스를 받을 때 적은 번호와 같으면 맞다")
		@NotNull @Size(min = 1, max = MAX_PHONES) List<@NotNull PhoneNumber> guardianPhones) {

	static final int MAX_PHONES = 4;

	// 칸 검증(@StudentNameText)을 거친 뒤에 부른다.
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
