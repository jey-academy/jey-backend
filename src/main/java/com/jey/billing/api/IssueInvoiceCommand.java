package com.jey.billing.api;

import java.time.YearMonth;
import java.util.List;
import java.util.Objects;

/**
 * 다른 모듈이 청구를 만들 때의 입력({@link InvoiceIssuing#issue}).
 *
 * <p>값의 한도는 {@link InvoiceRules}다. 한도 밖의 값(반 개수, 횟수, 대상 월, 같은 할인 두 번)은 부르는 쪽의 버그로 본다
 * ({@link IllegalArgumentException}). 부르는 쪽의 요청도 같은 한도로 먼저 거른다.
 *
 * @param discountCodes 직원이 고른 할인 유형 코드(예: 형제 할인). 근거가 필요한 할인을 여기로 보내면 거부한다
 *     ({@code BILLING_DISCOUNT_NEEDS_REFERENCE})
 * @param referencedDiscounts 근거(참조값)가 있는 할인. 근거가 필요한 할인만 받는다. 직원이 고른 할인보다 먼저 계산한다
 * @throws IllegalArgumentException 목록이 없거나 원소가 비었을 때
 */
public record IssueInvoiceCommand(long studentId, YearMonth targetMonth, List<Line> lines,
		List<String> discountCodes, List<InvoiceDiscount> referencedDiscounts) {

	public IssueInvoiceCommand {
		if (lines == null || discountCodes == null || referencedDiscounts == null) {
			throw new IllegalArgumentException("lines, discountCodes, referencedDiscounts는 비울 수 없다(없으면 빈 목록)");
		}
		// List.of로 만든 목록은 contains(null)에서 예외를 던지므로 하나씩 본다.
		if (lines.stream().anyMatch(Objects::isNull) || discountCodes.stream().anyMatch(Objects::isNull)
				|| referencedDiscounts.stream().anyMatch(Objects::isNull)) {
			throw new IllegalArgumentException("목록의 원소는 비울 수 없다");
		}
		lines = List.copyOf(lines);
		discountCodes = List.copyOf(discountCodes);
		referencedDiscounts = List.copyOf(referencedDiscounts);
	}

	/** 반 하나와 그 달의 수업 횟수. */
	public record Line(long classId, int lessonCount) {
	}

}
