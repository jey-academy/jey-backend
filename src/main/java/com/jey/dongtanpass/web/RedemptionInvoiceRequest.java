package com.jey.dongtanpass.web;

import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;

import com.jey.billing.api.InvoiceRules;
import com.jey.billing.api.IssueInvoiceCommand;
import com.jey.core.shared.RejectUnknownFields;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// 모르는 칸은 400이다(할인율 같은 값을 보내도 조용히 무시되지 않게 — 패스 할인율은 서버가 정한다).
@RejectUnknownFields
@Schema(description = "패스 사용 기록에서 청구 만들기. 패스 할인은 서버가 붙인다(지금 할인율, 참조값 = 패스 번호). 반·횟수 규칙은 수납의 청구 만들기와 같다")
record RedemptionInvoiceRequest(
		@Schema(description = "대상 월(YYYY-MM). 2000-01~2099-12", type = "string", example = "2026-11") @NotNull YearMonth targetMonth,
		@Schema(description = "반과 그 달의 수업 횟수. 1~4개, 같은 반은 한 번") @NotNull @Size(min = 1,
				max = InvoiceRules.MAX_LINES) List<@Valid @NotNull LineRequest> lines,
		@Schema(description = "직원이 더 고르는 할인 유형 코드(예: SIBLING_5). 패스 할인 다음에 계산한다. 0~3개, 없으면 비우거나 보내지 않는다. 패스 할인 코드를 넣으면 400(BILLING_DISCOUNT_NEEDS_REFERENCE)") @Size(
				max = InvoiceRules.MAX_EXTRA_DISCOUNTS_WITH_PASS) List<@NotBlank @Size(
						max = InvoiceRules.MAX_DISCOUNT_CODE_LENGTH) String> discountCodes) {

	RedemptionInvoiceRequest {
		if (lines != null && lines.stream().allMatch(line -> line != null && line.classId() != null)
				&& new HashSet<>(lines.stream().map(LineRequest::classId).toList()).size() != lines.size()) {
			throw new IllegalArgumentException("같은 반을 두 번 넣을 수 없다");
		}
		if (discountCodes != null && new HashSet<>(discountCodes).size() != discountCodes.size()) {
			throw new IllegalArgumentException("같은 할인을 두 번 고를 수 없다");
		}
		if (targetMonth != null && !InvoiceRules.acceptsMonth(targetMonth)) {
			throw new IllegalArgumentException("대상 월은 2000-01부터 2099-12까지다");
		}
	}

	List<IssueInvoiceCommand.Line> commandLines() {
		return lines.stream().map(line -> new IssueInvoiceCommand.Line(line.classId(), line.lessonCount())).toList();
	}

	List<String> discountCodesOrEmpty() {
		return discountCodes == null ? List.of() : discountCodes;
	}

	@Schema(description = "청구 라인 하나의 입력")
	record LineRequest(@Schema(description = "반 ID. 학생과 같은 지점의 켜진 반") @NotNull Long classId,
			@Schema(description = "그 달의 수업 횟수. 1~31") @NotNull @Min(InvoiceRules.MIN_LESSONS) @Max(
					InvoiceRules.MAX_LESSONS) Integer lessonCount) {
	}

}
