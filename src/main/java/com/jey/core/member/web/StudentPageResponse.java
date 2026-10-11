package com.jey.core.member.web;

import java.util.List;

import com.jey.core.member.StudentSummaryView;
import com.jey.core.member.domain.Grade;
import com.jey.core.member.domain.StudentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import org.springframework.data.domain.Page;

@Schema(description = "학생 목록의 한 페이지. 이름 순이고 퇴원한 학생도 포함한다")
record StudentPageResponse(@Schema(requiredMode = RequiredMode.REQUIRED) List<StudentSummaryResponse> items,
		@Schema(description = "지금 페이지. 0부터", requiredMode = RequiredMode.REQUIRED) int page,
		@Schema(description = "한 페이지의 크기", requiredMode = RequiredMode.REQUIRED) int size,
		@Schema(description = "조건에 맞는 전체 학생 수", requiredMode = RequiredMode.REQUIRED) long totalElements,
		@Schema(requiredMode = RequiredMode.REQUIRED) int totalPages) {

	static StudentPageResponse from(Page<StudentSummaryView> page) {
		return new StudentPageResponse(page.getContent().stream().map(StudentSummaryResponse::from).toList(),
				page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
	}

	@Schema(description = "목록의 한 줄. 학부모 전체가 아니라 대표 연락처만 있다")
	record StudentSummaryResponse(@Schema(requiredMode = RequiredMode.REQUIRED) Long id,
			@Schema(requiredMode = RequiredMode.REQUIRED) Long campusId,
			@Schema(description = "반. 정하지 않았으면 null", requiredMode = RequiredMode.REQUIRED, nullable = true) Long classId,
			@Schema(requiredMode = RequiredMode.REQUIRED) String name,
			@Schema(description = "학교. 없으면 null", requiredMode = RequiredMode.REQUIRED, nullable = true) String school,
			@Schema(requiredMode = RequiredMode.REQUIRED) Grade grade,
			@Schema(requiredMode = RequiredMode.REQUIRED) StudentStatus status,
			@Schema(requiredMode = RequiredMode.REQUIRED) ContactResponse primaryContact) {

		static StudentSummaryResponse from(StudentSummaryView student) {
			return new StudentSummaryResponse(student.id(), student.campusId(), student.classId(), student.name(),
					student.school(), student.grade(), student.status(),
					ContactResponse.from(student.primaryContact()));
		}

	}

}
