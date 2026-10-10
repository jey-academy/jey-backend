package com.jey.core.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 금액을 어떤 단위로, 어느 방향으로 끊을지 나타낸다.
 *
 * @param unit 끊는 단위(원). 1이면 원 단위(소수점 이하를 끊음), 10이면 10원 단위로 맞춘다.
 * @param mode 끊는 방향. {@link RoundingMode#DOWN}은 버림이다.
 */
public record RoundingPolicy(long unit, RoundingMode mode) {

	/** 확정된 수강료 규칙: 최종 금액에서 소수점 이하(1원 미만)를 버린다. */
	public static final RoundingPolicy ONE_WON_DOWN = new RoundingPolicy(1, RoundingMode.DOWN);

	public RoundingPolicy {
		if (unit <= 0) {
			throw new IllegalArgumentException("unit은 양수여야 한다: " + unit);
		}
		Objects.requireNonNull(mode, "mode");
	}

	BigDecimal apply(BigDecimal amount) {
		return divide(amount, 1);
	}

	// 몫을 먼저 구해 자르지 않고, (나누는 수 × 단위)로 한 번에 나눠 정확한 몫에서 끊는다.
	BigDecimal divide(BigDecimal amount, long divisor) {
		BigDecimal unitAmount = BigDecimal.valueOf(unit);
		return amount.divide(BigDecimal.valueOf(divisor).multiply(unitAmount), 0, mode).multiply(unitAmount);
	}

}
