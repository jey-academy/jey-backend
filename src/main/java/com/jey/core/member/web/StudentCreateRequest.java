package com.jey.core.member.web;

import java.time.LocalDate;
import java.util.List;

import com.jey.core.member.StudentRegistration;
import com.jey.core.member.domain.Grade;
import com.jey.core.member.domain.Student;
import com.jey.core.shared.PhoneNumber;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "학생 등록. 학부모가 한 명 이상이거나 학생 본인 번호가 있어야 한다")
record StudentCreateRequest(@NotNull Long campusId,
		@Schema(description = "반. 아직 정하지 않았으면 보내지 않는다. 학생의 지점에서 운영 중인 반이어야 한다") Long classId,
		@NotBlank @Size(max = Student.MAX_NAME_LENGTH) String name,
		@Schema(description = "학교. 없으면 보내지 않는다") @Size(max = Student.MAX_SCHOOL_LENGTH) String school,
		@NotNull Grade grade,
		@Schema(description = "등록일. 보내지 않으면 오늘") LocalDate registeredAt,
		@Schema(description = "학생 본인의 휴대전화 번호. 없으면 보내지 않는다", example = "010-1234-5678") PhoneNumber phone,
		@Schema(description = "학부모. 같은 전화번호를 두 번 적을 수 없다") @Valid @Size(
				max = StudentCreateRequest.MAX_GUARDIANS) List<@NotNull @Valid GuardianRequest> guardians) {

	static final int MAX_GUARDIANS = 4;

	StudentRegistration toRegistration() {
		return new StudentRegistration(campusId, classId, name, school, grade, registeredAt, phone,
				(guardians == null) ? List.of() : guardians.stream().map(GuardianRequest::toInput).toList());
	}

}
