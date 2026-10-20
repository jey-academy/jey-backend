package com.jey.billing.application;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import com.jey.billing.domain.DiscountPolicy;
import com.jey.billing.domain.InvoiceStatus;
import com.jey.core.shared.Money;

/**
 * 계산했거나 만든 청구. 반·할인의 이름을 붙여 둔다. 8회 기준 수강료는 싣지 않는다(직원도 받는다).
 *
 * @param id 미리 계산이면 {@code null}
 * @param status 미리 계산이면 {@code null}
 * @param totalAmount 라인 청구액의 합
 */
public record InvoiceDetail(Long id, long campusId, long studentId, YearMonth targetMonth, InvoiceStatus status,
		DiscountPolicy discountPolicy, Money totalAmount, List<LineDetail> lines) {

	public InvoiceDetail {
		lines = List.copyOf(lines);
	}

	/**
	 * @param listAmount 할인 없는 그 횟수의 금액
	 */
	public record LineDetail(long classId, String className, int lessonCount, Money listAmount,
			List<DiscountDetail> discounts, Money chargeAmount) {

		public LineDetail {
			discounts = List.copyOf(discounts);
		}

	}

	/**
	 * @param amount 이 할인이 줄인 금액. 라인의 할인 금액을 더하면 정가 − 청구액이다
	 */
	public record DiscountDetail(String code, String name, BigDecimal rate, Money amount) {

		// DB에서 읽은 0.0500과 계산한 0.05가 같은 값이 되게 표현을 맞춘다(응답에는 0.05로 나간다).
		public DiscountDetail {
			rate = rate.stripTrailingZeros();
		}

	}

}
