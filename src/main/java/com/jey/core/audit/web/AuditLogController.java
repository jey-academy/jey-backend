package com.jey.core.audit.web;

import java.time.Instant;

import com.jey.core.audit.AuditLogService;
import com.jey.core.audit.api.AuditTarget;
import com.jey.core.audit.domain.AuditRecord;
import com.jey.core.auth.api.AllowedRoles;
import com.jey.core.auth.api.UserRole;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

// 관리자만 본다. 직원이 자기 기록을 볼 수 있으면 무엇이 남고 무엇이 안 남는지 지켜보며 움직일 수 있다.
// 기록을 고치거나 지우는 경로는 없다.
@RestController
@RequestMapping("/api/v1/audit-logs")
@AllowedRoles(UserRole.ADMIN)
class AuditLogController {

	static final int MAX_PAGE_SIZE = 100;

	private final AuditLogService auditLogService;

	private final JsonMapper jsonMapper;

	AuditLogController(AuditLogService auditLogService, JsonMapper jsonMapper) {
		this.auditLogService = auditLogService;
		this.jsonMapper = jsonMapper;
	}

	@GetMapping
	AuditLogPageResponse auditLogs(
			@Parameter(description = "그 일을 한 계정의 ID") @RequestParam(required = false) Long actorId,
			@Parameter(description = "행위 이름. 정확히 일치하는 것만 찾는다") @RequestParam(required = false) @Size(
					max = AuditRecord.MAX_ACTION_LENGTH) String action,
			@Parameter(description = "대상의 종류(예: student)") @RequestParam(required = false) @Size(
					max = AuditTarget.MAX_TYPE_LENGTH) String targetType,
			@RequestParam(required = false) Long targetId,
			@Parameter(description = "이 시각부터(포함). 시간대를 붙인 ISO 8601 형식",
					example = "2026-11-01T00:00:00+09:00") @RequestParam(required = false) @DateTimeFormat(
							iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
			@Parameter(description = "이 시각 전까지(제외). 시간대를 붙인 ISO 8601 형식",
					example = "2026-12-01T00:00:00+09:00") @RequestParam(required = false) @DateTimeFormat(
							iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
			@Parameter(description = "0부터") @RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
		return AuditLogPageResponse.from(
				auditLogService.search(actorId, action, targetType, targetId, from, to, page, size), jsonMapper);
	}

}
