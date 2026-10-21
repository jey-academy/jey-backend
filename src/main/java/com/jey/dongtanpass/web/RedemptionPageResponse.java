package com.jey.dongtanpass.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.jey.dongtanpass.application.RedemptionRow;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import org.springframework.data.domain.Page;

@Schema(description = "패스 사용 내역의 한 페이지. 최신 사용 순이다")
record RedemptionPageResponse(@Schema(requiredMode = RequiredMode.REQUIRED) List<RedemptionItemResponse> items,
		@Schema(description = "지금 페이지. 0부터", requiredMode = RequiredMode.REQUIRED) int page,
		@Schema(description = "한 페이지의 크기", requiredMode = RequiredMode.REQUIRED) int size,
		@Schema(description = "조건에 맞는 전체 건수", requiredMode = RequiredMode.REQUIRED) long totalElements,
		@Schema(requiredMode = RequiredMode.REQUIRED) int totalPages) {

	static RedemptionPageResponse from(Page<RedemptionRow> page) {
		return new RedemptionPageResponse(page.getContent().stream().map(RedemptionItemResponse::from).toList(),
				page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
	}

	@Schema(description = "패스를 쓴 기록 하나. 학부모 번호는 없다")
	record RedemptionItemResponse(
			@Schema(description = "사용 기록 ID", requiredMode = RequiredMode.REQUIRED) long redemptionId,
			@Schema(description = "사용 처리한 시각", requiredMode = RequiredMode.REQUIRED) Instant redeemedAt,
			@Schema(description = "사용일(한국 날짜). 기간 조건은 이 날짜로 자른다", requiredMode = RequiredMode.REQUIRED) LocalDate redeemedOn,
			@Schema(description = "쓴 지점", requiredMode = RequiredMode.REQUIRED) long campusId,
			@Schema(description = "패스 ID", requiredMode = RequiredMode.REQUIRED) long passId,
			@Schema(description = "패스 번호", requiredMode = RequiredMode.REQUIRED) String passNo,
			@Schema(description = "패스를 발급한 연계 학원", requiredMode = RequiredMode.REQUIRED) String partnerName,
			@Schema(description = "학생 ID", requiredMode = RequiredMode.REQUIRED) long studentId,
			@Schema(description = "학생 이름. 학생을 찾지 못하면 null(있어서는 안 되는 자료)", requiredMode = RequiredMode.REQUIRED,
					nullable = true) String studentName,
			@Schema(description = "처리한 직원의 계정 ID. 로그인 없이 넣은 기록이면 null", requiredMode = RequiredMode.REQUIRED,
					nullable = true) Long staffId,
			@Schema(description = "이 사용 기록에서 만든 열린 청구의 ID(결제 전이거나 결제한 청구). 만들지 않았거나 취소했으면 null",
					requiredMode = RequiredMode.REQUIRED, nullable = true) Long invoiceId) {

		static RedemptionItemResponse from(RedemptionRow row) {
			return new RedemptionItemResponse(row.redemptionId(), row.redeemedAt(), row.redeemedOn(), row.campusId(),
					row.passId(), row.passNo(), row.partnerName(), row.studentId(), row.studentName(), row.staffId(),
					row.invoiceId());
		}

	}

}
