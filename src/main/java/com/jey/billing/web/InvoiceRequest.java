package com.jey.billing.web;

import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;

import com.jey.billing.application.InvoiceDraft;
import com.jey.billing.domain.Invoice;
import com.jey.billing.domain.InvoiceLine;
import com.jey.billing.domain.TuitionCalculator;
import com.jey.core.shared.RejectUnknownFields;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// 모르는 칸은 400이다(할인 칸의 오타가 할인 없는 청구로 계산되지 않게).
@RejectUnknownFields
@Schema(description = "청구 계산·만들기의 입력. 할인은 청구 전체에 고르고 반마다 같은 할인이 붙는다")
record InvoiceRequest(@Schema(description = "학생 ID. 재원 중이어야 한다") @NotNull Long studentId,
		@Schema(description = "대상 월(YYYY-MM). 2000-01~2099-12", type = "string", example = "2026-11") @NotNull YearMonth targetMonth,
		@Schema(description = "반과 그 달의 수업 횟수. 1~4개, 같은 반은 한 번. 반 나눔이면 둘 이상") @NotNull @Size(min = 1,
				max = Invoice.MAX_LINES) List<@Valid @NotNull InvoiceLineRequest> lines,
		@Schema(description = "할인 유형 코드(예: SIBLING_5). 0~4개, 같은 할인은 한 번. 고른 순서대로 계산한다(순차 방식이면 금액이 달라진다). 없으면 비우거나 보내지 않는다") @Size(
				max = InvoiceLine.MAX_DISCOUNTS) List<@NotBlank @Size(max = 30) String> discountCodes) {

	// 겹침은 요청을 읽을 때 본다(칸 하나의 값이 아니라 칸 사이의 규칙이라 애너테이션으로 적지 못한다).
	InvoiceRequest {
		if (lines != null && lines.stream().allMatch(line -> line != null && line.classId() != null)
				&& new HashSet<>(lines.stream().map(InvoiceLineRequest::classId).toList()).size() != lines.size()) {
			throw new IllegalArgumentException("같은 반을 두 번 넣을 수 없다");
		}
		if (discountCodes != null && new HashSet<>(discountCodes).size() != discountCodes.size()) {
			throw new IllegalArgumentException("같은 할인을 두 번 고를 수 없다");
		}
		if (targetMonth != null && (targetMonth.isBefore(InvoiceDraft.MIN_MONTH)
				|| targetMonth.isAfter(InvoiceDraft.MAX_MONTH))) {
			throw new IllegalArgumentException("대상 월은 2000-01부터 2099-12까지다");
		}
	}

	InvoiceDraft toDraft() {
		return new InvoiceDraft(studentId, targetMonth,
				lines.stream().map(line -> new InvoiceDraft.LineDraft(line.classId(), line.lessonCount())).toList(),
				discountCodes == null ? List.of() : discountCodes);
	}

	@Schema(description = "청구 라인 하나의 입력")
	record InvoiceLineRequest(@Schema(description = "반 ID. 학생과 같은 지점의 켜진 반") @NotNull Long classId,
			@Schema(description = "그 달의 수업 횟수. 1~31") @NotNull @Min(TuitionCalculator.MIN_LESSONS) @Max(
					TuitionCalculator.MAX_LESSONS) Integer lessonCount) {
	}

}
