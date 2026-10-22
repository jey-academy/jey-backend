package com.jey.dongtanpass.web;

import java.util.List;

import org.springframework.data.domain.Page;

import com.jey.dongtanpass.application.PartnerLedgerSyncView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "연계 학원 장부 미반영 목록의 한 페이지. 최근에 생긴 반영 기록부터")
record PartnerLedgerSyncPageResponse(
		@Schema(requiredMode = RequiredMode.REQUIRED) List<PartnerLedgerSyncResponse> items,
		@Schema(description = "지금 페이지. 0부터", requiredMode = RequiredMode.REQUIRED) int page,
		@Schema(description = "한 페이지의 크기", requiredMode = RequiredMode.REQUIRED) int size,
		@Schema(description = "조건에 맞는 전체 건수", requiredMode = RequiredMode.REQUIRED) long totalElements,
		@Schema(requiredMode = RequiredMode.REQUIRED) int totalPages) {

	static PartnerLedgerSyncPageResponse from(Page<PartnerLedgerSyncView> page) {
		return new PartnerLedgerSyncPageResponse(page.getContent().stream().map(PartnerLedgerSyncResponse::from).toList(),
				page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
	}

}
