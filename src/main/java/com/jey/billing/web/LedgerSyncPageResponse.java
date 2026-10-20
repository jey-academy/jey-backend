package com.jey.billing.web;

import java.util.List;

import org.springframework.data.domain.Page;

import com.jey.billing.application.LedgerSyncView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "장부 미반영 목록의 한 페이지. 최근 결제부터")
record LedgerSyncPageResponse(@Schema(requiredMode = RequiredMode.REQUIRED) List<LedgerSyncResponse> items,
		@Schema(description = "지금 페이지. 0부터", requiredMode = RequiredMode.REQUIRED) int page,
		@Schema(description = "한 페이지의 크기", requiredMode = RequiredMode.REQUIRED) int size,
		@Schema(description = "조건에 맞는 전체 건수", requiredMode = RequiredMode.REQUIRED) long totalElements,
		@Schema(requiredMode = RequiredMode.REQUIRED) int totalPages) {

	static LedgerSyncPageResponse from(Page<LedgerSyncView> page) {
		return new LedgerSyncPageResponse(page.getContent().stream().map(LedgerSyncResponse::from).toList(),
				page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
	}

}
