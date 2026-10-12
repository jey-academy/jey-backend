package com.jey.core.member.web;

import java.time.LocalDate;
import java.util.List;

import com.jey.core.member.StudentView;
import com.jey.core.member.StudentView.SiblingView;
import com.jey.core.member.domain.Grade;
import com.jey.core.member.domain.StudentStatus;
import com.jey.core.shared.PhoneNumber;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "학생 한 명의 전체 정보. 전화번호는 모두 가운데를 가린 표시다")
record StudentResponse(@Schema(requiredMode = RequiredMode.REQUIRED) Long id,
		@Schema(requiredMode = RequiredMode.REQUIRED) Long campusId,
		@Schema(description = "반. 정하지 않았으면 null", requiredMode = RequiredMode.REQUIRED, nullable = true) Long classId,
		@Schema(requiredMode = RequiredMode.REQUIRED) String name,
		@Schema(description = "학교. 없으면 null", requiredMode = RequiredMode.REQUIRED, nullable = true) String school,
		@Schema(requiredMode = RequiredMode.REQUIRED) Grade grade,
		@Schema(requiredMode = RequiredMode.REQUIRED) StudentStatus status,
		@Schema(requiredMode = RequiredMode.REQUIRED) LocalDate registeredAt,
		@Schema(description = "학생 본인의 가린 전화번호. 없으면 null", example = "010-****-5678",
				requiredMode = RequiredMode.REQUIRED, nullable = true) PhoneNumber phone,
		@Schema(description = "학부모. 먼저 연결된 순서", requiredMode = RequiredMode.REQUIRED) List<GuardianResponse> guardians,
		@Schema(description = "대표 연락처. 연락처가 하나도 없는 학생이면 null이다(있어서는 안 되는 자료다. 칸을 비워 보여 준다). 필드는 항상 있다",
				requiredMode = RequiredMode.REQUIRED, nullable = true) ContactResponse primaryContact,
		@Schema(description = "형제. 같은 학부모에 연결된 다른 학생",
				requiredMode = RequiredMode.REQUIRED) List<SiblingResponse> siblings) {

	static StudentResponse from(StudentView student) {
		return new StudentResponse(student.id(), student.campusId(), student.classId(), student.name(),
				student.school(), student.grade(), student.status(), student.registeredAt(), student.phone(),
				student.guardians().stream().map(GuardianResponse::from).toList(),
				ContactResponse.from(student.primaryContact()),
				student.siblings().stream().map(SiblingResponse::from).toList());
	}

	record SiblingResponse(@Schema(requiredMode = RequiredMode.REQUIRED) Long id,
			@Schema(requiredMode = RequiredMode.REQUIRED) String name,
			@Schema(requiredMode = RequiredMode.REQUIRED) StudentStatus status) {

		static SiblingResponse from(SiblingView sibling) {
			return new SiblingResponse(sibling.id(), sibling.name(), sibling.status());
		}

	}

}
