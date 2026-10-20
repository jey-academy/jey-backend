package com.jey.billing.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import com.jey.billing.api.InvoiceRules;
import com.jey.core.shared.Money;
import com.jey.core.shared.RoundingPolicy;

/**
 * 수강료 계산. 청구액 = 기준 수강료(8회분) × (1 − 할인율) × 횟수 ÷ 8, 최종 금액에서 1원 미만을 버린다(ADR-0015).
 * 곱셈을 모두 끝낸 뒤 마지막에 한 번만 나누고 끊는다.
 *
 * <p>할인마다의 금액은 할인을 받은 순서대로 하나씩 더 적용했을 때 줄어든 만큼이다. 각 단계의 금액을 끊은 값끼리 빼므로
 * 할인 금액을 더하면 정가와 청구액의 차이와 언제나 같다.
 */
public final class TuitionCalculator {

	public static final int MIN_LESSONS = InvoiceRules.MIN_LESSONS;

	public static final int MAX_LESSONS = InvoiceRules.MAX_LESSONS;

	private static final int BASE_LESSONS = 8;

	private static final RoundingPolicy ROUNDING = RoundingPolicy.ONE_WON_DOWN;

	private TuitionCalculator() {
	}

	/**
	 * @param baseFee8 8회 기준 수강료. 0원보다 크고 1원 단위
	 * @param lessonCount 수업 횟수({@value #MIN_LESSONS}~{@value #MAX_LESSONS})
	 * @param discounts 받은 순서대로. 같은 코드를 두 번 받지 않는다
	 * @throws IllegalArgumentException 위 조건을 어길 때
	 * @throws TooManyDiscountsException {@link DiscountPolicy#SINGLE}에 할인이 둘 이상일 때
	 * @throws DiscountTooLargeException {@link DiscountPolicy#ADDITIVE}에서 할인율의 합이 1 이상일 때
	 */
	public static LineCharge charge(Money baseFee8, int lessonCount, List<DiscountRate> discounts,
			DiscountPolicy policy) {
		Objects.requireNonNull(policy, "policy");
		if (baseFee8 == null || baseFee8.isZero() || !baseFee8.isWholeWon()) {
			throw new IllegalArgumentException("기준 수강료는 0원보다 크고 1원 단위여야 한다");
		}
		if (lessonCount < MIN_LESSONS || lessonCount > MAX_LESSONS) {
			throw new IllegalArgumentException("수업 횟수는 " + MIN_LESSONS + "~" + MAX_LESSONS + "회다");
		}
		if (new HashSet<>(discounts.stream().map(DiscountRate::code).toList()).size() != discounts.size()) {
			throw new IllegalArgumentException("같은 할인을 두 번 받을 수 없다");
		}
		if (policy == DiscountPolicy.SINGLE && discounts.size() > 1) {
			throw new TooManyDiscountsException();
		}
		if (policy == DiscountPolicy.ADDITIVE
				&& discounts.stream().map(DiscountRate::rate).reduce(BigDecimal.ZERO, BigDecimal::add)
					.compareTo(BigDecimal.ONE) >= 0) {
			throw new DiscountTooLargeException();
		}

		Money list = amountAt(baseFee8, BigDecimal.ONE, lessonCount);
		List<AppliedDiscount> applied = new ArrayList<>();
		BigDecimal remaining = BigDecimal.ONE;
		Money previous = list;
		for (DiscountRate discount : discounts) {
			remaining = policy == DiscountPolicy.SEQUENTIAL
					? remaining.multiply(BigDecimal.ONE.subtract(discount.rate()))
					: remaining.subtract(discount.rate());
			Money current = amountAt(baseFee8, remaining, lessonCount);
			applied.add(new AppliedDiscount(discount.code(), discount.rate(), previous.minus(current), discount.ref()));
			previous = current;
		}
		return new LineCharge(list, applied, previous);
	}

	// 기준 수강료 × 남은 비율 × 횟수 ÷ 8. 나누기와 끊기는 마지막에 한 번.
	private static Money amountAt(Money baseFee8, BigDecimal remainingRate, int lessonCount) {
		return baseFee8.times(remainingRate).times(lessonCount).dividedBy(BASE_LESSONS, ROUNDING);
	}

}
