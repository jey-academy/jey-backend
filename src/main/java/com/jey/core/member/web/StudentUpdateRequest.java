package com.jey.core.member.web;

import java.time.LocalDate;

import com.jey.core.member.StudentChange;
import com.jey.core.member.domain.Grade;
import com.jey.core.member.domain.Student;
import com.jey.core.member.domain.StudentStatus;
import com.jey.core.shared.PhoneNumber;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "학생 정보 수정. 전화번호를 뺀 값은 통째로 바꾼다(보내지 않은 반과 학교는 비워진다)")
record StudentUpdateRequest(@NotNull Long campusId,
		@Schema(description = "반. 보내지 않으면 반 없음이 된다. 학생의 지점에 속한 반이어야 하고, 새로 옮기는 반은 운영 중이어야 한다") Long classId,
		@NotBlank @Size(max = Student.MAX_NAME_LENGTH) String name,
		@Schema(description = "학교. 보내지 않으면 비워진다") @Size(max = Student.MAX_SCHOOL_LENGTH) String school,
		@NotNull Grade grade, @NotNull StudentStatus status,
		@Schema(description = "등록일. 2000-01-01부터 2099-12-31까지") @NotNull @RegisteredDate LocalDate registeredAt,
		@Schema(description = "학생 본인의 새 휴대전화 번호. 보내지 않으면 그대로 둔다. 응답의 가린 번호를 다시 보내면 안 된다. "
				+ "번호를 지우려면 DELETE /students/{id}/phone", example = "010-1234-5678") PhoneNumber phone) {

	StudentChange toChange() {
		return new StudentChange(campusId, classId, name, school, grade, status, registeredAt, phone);
	}

}
