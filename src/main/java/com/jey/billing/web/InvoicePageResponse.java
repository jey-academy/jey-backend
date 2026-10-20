package com.jey.billing.web;

import java.util.List;

import org.springframework.data.domain.Page;

import com.jey.billing.application.InvoiceDetail;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "그 달 청구의 한 페이지. 학생 ID 순이다")
record InvoicePageResponse(@Schema(requiredMode = RequiredMode.REQUIRED) List<InvoiceResponse> items,
		@Schema(description = "지금 페이지. 0부터", requiredMode = RequiredMode.REQUIRED) int page,
		@Schema(description = "한 페이지의 크기", requiredMode = RequiredMode.REQUIRED) int size,
		@Schema(description = "조건에 맞는 전체 건수", requiredMode = RequiredMode.REQUIRED) long totalElements,
		@Schema(requiredMode = RequiredMode.REQUIRED) int totalPages) {

	static InvoicePageResponse from(Page<InvoiceDetail> page) {
		return new InvoicePageResponse(page.getContent().stream().map(InvoiceResponse::from).toList(),
				page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
	}

}
