package com.jey.billing.web;

import java.util.List;

import org.springframework.data.domain.Page;

import com.jey.billing.application.PaymentView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "결제 목록의 한 페이지. 최신 결제 순이다. 취소한 결제도 있다")
record PaymentPageResponse(@Schema(requiredMode = RequiredMode.REQUIRED) List<PaymentResponse> items,
		@Schema(description = "지금 페이지. 0부터", requiredMode = RequiredMode.REQUIRED) int page,
		@Schema(description = "한 페이지의 크기", requiredMode = RequiredMode.REQUIRED) int size,
		@Schema(description = "조건에 맞는 전체 건수", requiredMode = RequiredMode.REQUIRED) long totalElements,
		@Schema(requiredMode = RequiredMode.REQUIRED) int totalPages) {

	static PaymentPageResponse from(Page<PaymentView> page) {
		return new PaymentPageResponse(page.getContent().stream().map(PaymentResponse::from).toList(),
				page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
	}

}
