package com.jey.core.audit.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.jey.core.audit.domain.AuditRecord;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import org.springframework.data.domain.Page;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Schema(description = "감사 로그의 한 페이지. 최신순이다")
record AuditLogPageResponse(@Schema(requiredMode = RequiredMode.REQUIRED) List<AuditLogResponse> items,
		@Schema(description = "지금 페이지. 0부터", requiredMode = RequiredMode.REQUIRED) int page,
		@Schema(description = "한 페이지의 크기", requiredMode = RequiredMode.REQUIRED) int size,
		@Schema(description = "조건에 맞는 전체 건수", requiredMode = RequiredMode.REQUIRED) long totalElements,
		@Schema(requiredMode = RequiredMode.REQUIRED) int totalPages) {

	private static final TypeReference<Map<String, Object>> DETAIL = new TypeReference<>() {
	};

	static AuditLogPageResponse from(Page<AuditRecord> page, JsonMapper jsonMapper) {
		return new AuditLogPageResponse(
				page.getContent().stream().map(each -> AuditLogResponse.from(each, jsonMapper)).toList(),
				page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
	}

	@Schema(description = "누가 언제 무엇에 무엇을 했는지 한 줄. 개인정보(이름, 전화번호)는 없고 ID만 있다")
	record AuditLogResponse(@Schema(requiredMode = RequiredMode.REQUIRED) Long id,
			@Schema(description = "그 일이 일어난 시각(UTC)", requiredMode = RequiredMode.REQUIRED) Instant occurredAt,
			@Schema(description = "그 일을 한 계정의 ID. 로그인 없이 일어난 일이면 null") Long actorId,
			@Schema(description = "무엇을 했는지. {모듈}.{사건} 형식(예: member.guardian-phone-duplicated)",
					requiredMode = RequiredMode.REQUIRED) String action,
			@Schema(description = "대상의 종류(예: student, guardian). 없으면 null") String targetType,
			@Schema(description = "대상의 ID. 없으면 null") Long targetId,
			@Schema(description = "그 일이 속한 지점. 지점과 무관하면 null") Long campusId,
			@Schema(description = "덧붙인 내용. 행위마다 키가 다르다. 없으면 null") Map<String, Object> detail) {

		static AuditLogResponse from(AuditRecord record, JsonMapper jsonMapper) {
			return new AuditLogResponse(record.getId(), record.getOccurredAt(), record.getActorId(),
					record.getAction(), record.getTargetType(), record.getTargetId(), record.getCampusId(),
					(record.getDetailJson() != null) ? jsonMapper.readValue(record.getDetailJson(), DETAIL) : null);
		}

	}

}
