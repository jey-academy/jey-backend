package com.jey.core.audit.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.jey.core.audit.api.AuditOutcome;
import com.jey.core.audit.domain.AuditRecord;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import org.springframework.data.domain.Page;

@Schema(description = "감사 로그의 한 페이지. 최신순이다")
record AuditLogPageResponse(@Schema(requiredMode = RequiredMode.REQUIRED) List<AuditLogResponse> items,
		@Schema(description = "지금 페이지. 0부터", requiredMode = RequiredMode.REQUIRED) int page,
		@Schema(description = "한 페이지의 크기", requiredMode = RequiredMode.REQUIRED) int size,
		@Schema(description = "조건에 맞는 전체 건수", requiredMode = RequiredMode.REQUIRED) long totalElements,
		@Schema(requiredMode = RequiredMode.REQUIRED) int totalPages) {

	/**
	 * @param detailOf 저장된 상세를 읽는 방법
	 */
	static AuditLogPageResponse from(Page<AuditRecord> page, Function<AuditRecord, Map<String, Object>> detailOf) {
		return new AuditLogPageResponse(
				page.getContent().stream().map(each -> AuditLogResponse.from(each, detailOf.apply(each))).toList(),
				page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
	}

	@Schema(description = "누가 언제 무엇에 무엇을 했는지 한 줄. 개인정보(이름, 전화번호)는 없고 ID만 있다")
	record AuditLogResponse(@Schema(requiredMode = RequiredMode.REQUIRED) Long id,
			@Schema(description = "그 일이 일어난 시각(UTC)", requiredMode = RequiredMode.REQUIRED) Instant occurredAt,
			@Schema(description = "그 일을 한 계정의 ID. 로그인 없이 일어난 일이면 null", requiredMode = RequiredMode.REQUIRED, nullable = true) Long actorId,
			@Schema(description = "무엇을 했는지. {모듈}.{사건} 형식(예: member.guardian-phone-duplicated)",
					requiredMode = RequiredMode.REQUIRED) String action,
			@Schema(description = "DONE은 실제로 한 일, DENIED는 거부된 시도",
					requiredMode = RequiredMode.REQUIRED) AuditOutcome outcome,
			@Schema(description = "대상의 종류(예: student, guardian). 없으면 null",
					requiredMode = RequiredMode.REQUIRED, nullable = true) String targetType,
			@Schema(description = "대상의 ID. 없으면 null", requiredMode = RequiredMode.REQUIRED, nullable = true) Long targetId,
			@Schema(description = "그 일이 속한 지점. 지점과 무관하면 null", requiredMode = RequiredMode.REQUIRED, nullable = true) Long campusId,
			@Schema(description = "덧붙인 내용. 행위마다 키가 다르다. 없으면 null. 내용을 싣지 못했으면 표시 하나만 들어 있다: "
					+ "{\"truncated\": true}(너무 커서 싣지 않음), {\"redacted\": true}(개인정보로 보이는 값이 있어 싣지 않음), "
					+ "{\"unreadable\": true}(적을 때 내용을 읽지 못함), {\"corrupted\": true}(저장된 값이 깨져 있음)",
					requiredMode = RequiredMode.REQUIRED, nullable = true) Map<String, Object> detail) {

		static AuditLogResponse from(AuditRecord record, Map<String, Object> detail) {
			return new AuditLogResponse(record.getId(), record.getOccurredAt(), record.getActorId(),
					record.getAction(), record.getOutcome(), record.getTargetType(), record.getTargetId(),
					record.getCampusId(), detail);
		}

	}

}
