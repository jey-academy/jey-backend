package com.jey.billing.web;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import com.jey.billing.application.InvoiceDetail;
import com.jey.billing.domain.DiscountPolicy;
import com.jey.billing.domain.InvoiceStatus;
import com.jey.core.shared.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "수강료 청구(또는 미리 계산한 결과). 금액은 원 단위 숫자. 8회 기준 수강료는 싣지 않는다")
record InvoiceResponse(
		@Schema(description = "청구 ID. 미리 계산이면 null", requiredMode = RequiredMode.REQUIRED, nullable = true) Long id,
		@Schema(description = "청구가 속한 지점(학생의 지점)", requiredMode = RequiredMode.REQUIRED) long campusId,
		@Schema(description = "학생 ID", requiredMode = RequiredMode.REQUIRED) long studentId,
		@Schema(description = "대상 월(YYYY-MM)", type = "string", example = "2026-11",
				requiredMode = RequiredMode.REQUIRED) YearMonth targetMonth,
		@Schema(description = "상태. ISSUED 결제 전, PAID 결제함(결제를 취소하면 ISSUED로 돌아간다), CANCELED 취소함(같은 달에 새 청구를 만들 수 있다). 미리 계산이면 null", requiredMode = RequiredMode.REQUIRED,
				nullable = true) InvoiceStatus status,
		@Schema(description = "할인이 여러 개일 때의 계산 방식. ADDITIVE 할인율을 더해 한 번에, SEQUENTIAL 앞의 할인을 뺀 금액에서 다음 할인, SINGLE 하나만. 만든 청구는 만들 때의 방식이다",
				requiredMode = RequiredMode.REQUIRED) DiscountPolicy discountPolicy,
		@Schema(description = "청구 합계(원). 라인 청구액의 합", requiredMode = RequiredMode.REQUIRED) Money totalAmount,
		@Schema(requiredMode = RequiredMode.REQUIRED) List<InvoiceLineResponse> lines,
		@Schema(description = "유효한 결제(COMPLETED). 청구가 PAID일 때만 있다(결제 전·취소한 청구, 미리 계산이면 null). 취소한 결제는 결제 목록에서 본다",
				requiredMode = RequiredMode.REQUIRED, nullable = true) PaymentResponse payment) {

	static InvoiceResponse from(InvoiceDetail detail) {
		return new InvoiceResponse(detail.id(), detail.campusId(), detail.studentId(), detail.targetMonth(),
				detail.status(), detail.discountPolicy(), detail.totalAmount(),
				detail.lines().stream().map(InvoiceLineResponse::from).toList(),
				detail.payment() == null ? null : PaymentResponse.from(detail.payment()));
	}

	@Schema(description = "청구 라인 하나(반 하나). 정가 = 청구액 + 할인 금액의 합")
	record InvoiceLineResponse(@Schema(description = "반 ID", requiredMode = RequiredMode.REQUIRED) long classId,
			@Schema(description = "반 이름(지금 이름)", requiredMode = RequiredMode.REQUIRED) String className,
			@Schema(description = "그 달의 수업 횟수", requiredMode = RequiredMode.REQUIRED) int lessonCount,
			@Schema(description = "할인 없는 그 횟수의 금액(원). 1원 미만 버림", requiredMode = RequiredMode.REQUIRED) Money listAmount,
			@Schema(description = "고른 순서대로", requiredMode = RequiredMode.REQUIRED) List<InvoiceDiscountResponse> discounts,
			@Schema(description = "청구액(원). 1원 미만 버림", requiredMode = RequiredMode.REQUIRED) Money chargeAmount) {

		static InvoiceLineResponse from(InvoiceDetail.LineDetail line) {
			return new InvoiceLineResponse(line.classId(), line.className(), line.lessonCount(), line.listAmount(),
					line.discounts().stream().map(InvoiceDiscountResponse::from).toList(), line.chargeAmount());
		}

	}

	@Schema(description = "라인에 적용한 할인 하나")
	record InvoiceDiscountResponse(@Schema(description = "할인 유형 코드", requiredMode = RequiredMode.REQUIRED) String code,
			@Schema(description = "할인 유형 이름(지금 이름)", requiredMode = RequiredMode.REQUIRED) String name,
			@Schema(description = "할인율. 5%는 0.05", requiredMode = RequiredMode.REQUIRED) BigDecimal rate,
			@Schema(description = "이 할인이 줄인 금액(원). 할인을 순서대로 하나씩 더 적용했을 때 줄어든 만큼",
					requiredMode = RequiredMode.REQUIRED) Money amount) {

		static InvoiceDiscountResponse from(InvoiceDetail.DiscountDetail discount) {
			return new InvoiceDiscountResponse(discount.code(), discount.name(), discount.rate(), discount.amount());
		}

	}

}
