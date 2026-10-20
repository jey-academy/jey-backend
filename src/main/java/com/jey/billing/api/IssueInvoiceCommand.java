package com.jey.billing.api;

import java.time.YearMonth;
import java.util.List;

/**
 * 다른 모듈이 청구를 만들 때의 입력({@link InvoiceIssuing#issue}).
 *
 * @param discountCodes 직원이 고른 할인 유형 코드(예: 형제 할인). 근거가 필요한 할인은 여기로 보내지 않는다
 * @param referencedDiscounts 근거(참조값)가 있는 할인. 직원이 고른 할인보다 먼저 계산한다
 */
public record IssueInvoiceCommand(long studentId, YearMonth targetMonth, List<Line> lines,
		List<String> discountCodes, List<InvoiceDiscount> referencedDiscounts) {

	public IssueInvoiceCommand {
		lines = List.copyOf(lines);
		discountCodes = List.copyOf(discountCodes);
		referencedDiscounts = List.copyOf(referencedDiscounts);
	}

	/** 반 하나와 그 달의 수업 횟수. */
	public record Line(long classId, int lessonCount) {
	}

}
