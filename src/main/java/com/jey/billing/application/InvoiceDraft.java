package com.jey.billing.application;

import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;

import com.jey.billing.domain.Invoice;
import com.jey.billing.domain.InvoiceLine;
import com.jey.billing.domain.TuitionCalculator;

/**
 * 청구를 계산하거나 만들 때의 입력. 할인은 청구 전체에 고르고 라인마다 같은 할인이 붙는다.
 *
 * @param discountCodes 할인 유형 코드. 고른 순서대로 계산한다(순차 방식에서 금액이 달라진다)
 * @throws IllegalArgumentException 값이 규칙 밖일 때(요청에서 오면 400)
 */
public record InvoiceDraft(long studentId, YearMonth targetMonth, List<LineDraft> lines, List<String> discountCodes) {

	public static final YearMonth MIN_MONTH = YearMonth.of(2000, 1);

	public static final YearMonth MAX_MONTH = YearMonth.of(2099, 12);

	public InvoiceDraft {
		if (targetMonth == null || targetMonth.isBefore(MIN_MONTH) || targetMonth.isAfter(MAX_MONTH)) {
			throw new IllegalArgumentException("대상 월은 2000-01부터 2099-12까지다");
		}
		if (lines == null || lines.isEmpty() || lines.size() > Invoice.MAX_LINES) {
			throw new IllegalArgumentException("반은 1~" + Invoice.MAX_LINES + "개다");
		}
		lines = List.copyOf(lines);
		if (new HashSet<>(lines.stream().map(LineDraft::classId).toList()).size() != lines.size()) {
			throw new IllegalArgumentException("같은 반을 두 번 넣을 수 없다");
		}
		if (discountCodes == null || discountCodes.size() > InvoiceLine.MAX_DISCOUNTS) {
			throw new IllegalArgumentException("할인은 " + InvoiceLine.MAX_DISCOUNTS + "개까지다");
		}
		discountCodes = List.copyOf(discountCodes);
		if (discountCodes.stream().anyMatch(code -> code == null || code.isBlank())
				|| new HashSet<>(discountCodes).size() != discountCodes.size()) {
			throw new IllegalArgumentException("할인 코드는 비울 수 없고 같은 할인을 두 번 고를 수 없다");
		}
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
