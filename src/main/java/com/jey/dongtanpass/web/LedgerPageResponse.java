package com.jey.dongtanpass.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.jey.core.shared.Money;
import com.jey.dongtanpass.application.LedgerRow;
import com.jey.dongtanpass.domain.PassState;
import com.jey.dongtanpass.domain.PaymentMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import org.springframework.data.domain.Page;

@Schema(description = "발급 장부의 한 페이지. 최신 발급 순이다")
record LedgerPageResponse(@Schema(requiredMode = RequiredMode.REQUIRED) List<LedgerItemResponse> items,
		@Schema(description = "지금 페이지. 0부터", requiredMode = RequiredMode.REQUIRED) int page,
		@Schema(description = "한 페이지의 크기", requiredMode = RequiredMode.REQUIRED) int size,
		@Schema(description = "조건에 맞는 전체 건수", requiredMode = RequiredMode.REQUIRED) long totalElements,
		@Schema(requiredMode = RequiredMode.REQUIRED) int totalPages) {

	static LedgerPageResponse from(Page<LedgerRow> page) {
		return new LedgerPageResponse(page.getContent().stream().map(LedgerItemResponse::from).toList(),
				page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
	}

	@Schema(description = "발급한 패스 하나. 학부모 번호는 없다")
	record LedgerItemResponse(@Schema(description = "패스 ID. 링크 새로 만들기와 취소에 쓴다", requiredMode = RequiredMode.REQUIRED) Long id,
			@Schema(description = "패스 번호", requiredMode = RequiredMode.REQUIRED) String passNo,
			@Schema(description = "학생 이름", requiredMode = RequiredMode.REQUIRED) String studentName,
			@Schema(description = "발급 시각", requiredMode = RequiredMode.REQUIRED) Instant issuedAt,
			@Schema(description = "발급일(한국 날짜). 기간 조건은 이 날짜로 자른다", requiredMode = RequiredMode.REQUIRED) LocalDate issuedOn,
			@Schema(description = "이날(한국 날짜)까지 쓸 수 있다", requiredMode = RequiredMode.REQUIRED) LocalDate expiresOn,
			@Schema(description = "오늘 기준으로 보이는 상태. EXPIRED는 쓰지 않은 채 만료일이 지난 패스",
					requiredMode = RequiredMode.REQUIRED) PassState state,
			@Schema(description = "결제일", requiredMode = RequiredMode.REQUIRED) LocalDate paidOn,
			@Schema(description = "결제 금액(원)", requiredMode = RequiredMode.REQUIRED) Money paidAmount,
			@Schema(description = "결제 수단", requiredMode = RequiredMode.REQUIRED) PaymentMethod paymentMethod) {

		static LedgerItemResponse from(LedgerRow row) {
			return new LedgerItemResponse(row.id(), row.passNo(), row.studentName(), row.issuedAt(), row.issuedOn(),
					row.expiresOn(), row.state(), row.paidOn(), row.paidAmount(), row.paymentMethod());
		}

	}

}
