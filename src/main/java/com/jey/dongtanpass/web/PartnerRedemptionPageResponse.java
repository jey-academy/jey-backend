package com.jey.dongtanpass.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.jey.core.shared.Money;
import com.jey.dongtanpass.application.PartnerRedemptionRow;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import org.springframework.data.domain.Page;

record PartnerRedemptionPageResponse(
		@Schema(requiredMode = RequiredMode.REQUIRED) List<PartnerRedemptionItemResponse> items,
		@Schema(description = "지금 페이지. 0부터", requiredMode = RequiredMode.REQUIRED) int page,
		@Schema(description = "한 페이지의 크기", requiredMode = RequiredMode.REQUIRED) int size,
		@Schema(description = "조건에 맞는 전체 건수", requiredMode = RequiredMode.REQUIRED) long totalElements,
		@Schema(requiredMode = RequiredMode.REQUIRED) int totalPages) {

	static PartnerRedemptionPageResponse from(Page<PartnerRedemptionRow> page) {
		return new PartnerRedemptionPageResponse(
				page.getContent().stream().map(PartnerRedemptionItemResponse::from).toList(), page.getNumber(),
				page.getSize(), page.getTotalElements(), page.getTotalPages());
	}

	@Schema(description = "연계 학원이 쓴 JEY 발급 패스 하나. 학부모 번호는 없다")
	record PartnerRedemptionItemResponse(
			@Schema(description = "사용 기록 ID", requiredMode = RequiredMode.REQUIRED) Long id,
			@Schema(description = "사용 처리한 시각", requiredMode = RequiredMode.REQUIRED) Instant redeemedAt,
			@Schema(description = "사용일(한국 날짜). 기간 조건은 이 날짜로 자른다", requiredMode = RequiredMode.REQUIRED) LocalDate redeemedOn,
			@Schema(description = "쓴 연계 학원", requiredMode = RequiredMode.REQUIRED) Long partnerId,
			@Schema(description = "쓴 연계 학원 이름", requiredMode = RequiredMode.REQUIRED) String partnerName,
			@Schema(description = "패스 ID", requiredMode = RequiredMode.REQUIRED) Long passId,
			@Schema(description = "패스 번호", requiredMode = RequiredMode.REQUIRED) String passNo,
			@Schema(description = "학생 이름(패스의 이름)", requiredMode = RequiredMode.REQUIRED) String studentName,
			@Schema(description = "원래 수강료(원)", requiredMode = RequiredMode.REQUIRED) Money originalFee,
			@Schema(description = "할인율(발급 때 값). 5%는 0.05", requiredMode = RequiredMode.REQUIRED) BigDecimal discountRate,
			@Schema(description = "할인액(원)", requiredMode = RequiredMode.REQUIRED) Money discountAmount,
			@Schema(description = "받을 금액(원). 1원 미만을 버린 값", requiredMode = RequiredMode.REQUIRED) Money payableAmount) {

		static PartnerRedemptionItemResponse from(PartnerRedemptionRow row) {
			return new PartnerRedemptionItemResponse(row.id(), row.redeemedAt(), row.redeemedOn(), row.partnerId(),
					row.partnerName(), row.passId(), row.passNo(), row.studentName(), row.originalFee(),
					row.discountRate(), row.discountAmount(), row.payableAmount());
		}

		// 학생 이름이 로그에 찍히지 않게 한다. 바깥 record도 이것을 쓴다.
		@Override
		public String toString() {
			return "PartnerRedemptionItemResponse[id=" + id + ", passId=" + passId + "]";
		}

	}

}
