package com.jey.billing.application;

import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;

import com.jey.billing.api.InvoiceRules;
import com.jey.billing.api.InvoiceDiscount;
import com.jey.billing.domain.Invoice;
import com.jey.billing.domain.InvoiceLine;
import com.jey.billing.domain.TuitionCalculator;

/**
 * 청구를 계산하거나 만들 때의 입력. 할인은 청구 전체에 고르고 라인마다 같은 할인이 붙는다.
 *
 * @param discountCodes 직원이 고른 할인 유형 코드. 고른 순서대로 계산한다(순차 방식에서 금액이 달라진다)
 * @param referencedDiscounts 다른 모듈이 근거(참조값)와 함께 넘긴 할인. 직원이 고른 할인보다 먼저 계산한다
 * @throws IllegalArgumentException 값이 규칙 밖일 때(요청에서 오면 400). 같은 할인 두 번은 서비스가 본다(BILLING_DISCOUNT_DUPLICATED)
 */
public record InvoiceDraft(long studentId, YearMonth targetMonth, List<LineDraft> lines, List<String> discountCodes,
		List<InvoiceDiscount> referencedDiscounts) {

	public InvoiceDraft {
		if (!InvoiceRules.acceptsMonth(targetMonth)) {
			throw new IllegalArgumentException("대상 월은 2000-01부터 2099-12까지다");
		}
		if (lines == null || lines.isEmpty() || lines.size() > Invoice.MAX_LINES) {
			throw new IllegalArgumentException("반은 1~" + Invoice.MAX_LINES + "개다");
		}
		lines = List.copyOf(lines);
		if (new HashSet<>(lines.stream().map(LineDraft::classId).toList()).size() != lines.size()) {
			throw new IllegalArgumentException("같은 반을 두 번 넣을 수 없다");
		}
		if (discountCodes == null || referencedDiscounts == null
				|| discountCodes.size() + referencedDiscounts.size() > InvoiceLine.MAX_DISCOUNTS) {
			throw new IllegalArgumentException("할인은 " + InvoiceLine.MAX_DISCOUNTS + "개까지다");
		}
		discountCodes = List.copyOf(discountCodes);
		referencedDiscounts = List.copyOf(referencedDiscounts);
		// 같은 할인이 두 번 있는지는 여기서 보지 않는다. 코드는 대소문자를 가리지 않고 찾으므로, 찾은 코드로 서비스가 업무 오류로 본다
		// (근거가 필요한 할인을 직원 선택 칸에 또 넣은 경우도 그쪽 안내가 먼저다).
		if (discountCodes.stream().anyMatch(code -> code == null || code.isBlank())) {
			throw new IllegalArgumentException("할인 코드는 비울 수 없다");
		}
	}

	/** 직원이 고른 할인만 있는 입력. */
	public InvoiceDraft(long studentId, YearMonth targetMonth, List<LineDraft> lines, List<String> discountCodes) {
		this(studentId, targetMonth, lines, discountCodes, List.of());
	}

	/**
	 * @param lessonCount 그 달의 수업 횟수
	 */
	public record LineDraft(long classId, int lessonCount) {

		public LineDraft {
			if (lessonCount < TuitionCalculator.MIN_LESSONS || lessonCount > TuitionCalculator.MAX_LESSONS) {
				throw new IllegalArgumentException(
						"수업 횟수는 " + TuitionCalculator.MIN_LESSONS + "~" + TuitionCalculator.MAX_LESSONS + "회다");
			}
		}

	}

}
