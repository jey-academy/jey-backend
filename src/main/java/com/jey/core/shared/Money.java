package com.jey.core.shared;

import java.math.BigDecimal;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 금액(원). 0 이상만 허용한다.
 * 곱셈·덧셈은 정밀도를 그대로 유지하고, 금액을 끊는 일은 {@link RoundingPolicy}를 넘긴 곳에서만 일어난다.
 * JSON에서는 숫자 하나({@code 299250})로 주고받는다.
 */
public record Money(@JsonValue BigDecimal amount) implements Comparable<Money> {

	public static final Money ZERO = new Money(BigDecimal.ZERO);

	public Money {
		Objects.requireNonNull(amount, "amount");
		if (amount.signum() < 0) {
			throw new IllegalArgumentException("금액은 음수일 수 없다: " + amount.toPlainString());
		}
		// BigDecimal은 1000.0과 1000을 다르게 본다. 금액이 같으면 같도록 표현을 하나로 맞춘다.
		amount = amount.stripTrailingZeros();
		// 정수 금액이 지수 표기(3E+5)가 되지 않게 한다.
		if (amount.scale() < 0) {
			amount = amount.setScale(0);
		}
	}

	@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
	public static Money of(BigDecimal amount) {
		return new Money(amount);
	}

	public static Money of(long amount) {
		return new Money(BigDecimal.valueOf(amount));
	}

	public Money plus(Money other) {
		return new Money(amount.add(other.amount));
	}

	/**
	 * @throws IllegalArgumentException 결과가 음수일 때
	 */
	public Money minus(Money other) {
		return new Money(amount.subtract(other.amount));
	}

	public Money times(long multiplier) {
		return new Money(amount.multiply(BigDecimal.valueOf(multiplier)));
	}

	/** 비율을 곱한다. 5% 할인은 {@code times(new BigDecimal("0.95"))}. */
	public Money times(BigDecimal multiplier) {
		return new Money(amount.multiply(multiplier));
	}

	/**
	 * 나누고 정책대로 끊는다. 나누어떨어지지 않아도 정확한 몫에서 끊으므로 오차가 없다.
	 * 나눈 금액에 다시 곱하면 오차가 생기므로, 곱셈을 모두 끝낸 뒤 마지막에 나눈다.
	 *
	 * @throws IllegalArgumentException 나누는 수가 0 이하일 때
	 */
	public Money dividedBy(long divisor, RoundingPolicy policy) {
		Objects.requireNonNull(policy, "policy");
		if (divisor <= 0) {
			throw new IllegalArgumentException("나누는 수는 양수여야 한다: " + divisor);
		}
		return new Money(policy.divide(amount, divisor));
	}

	/** 정책대로 금액을 끊는다. 계산이 모두 끝난 최종 금액에 쓴다. */
	public Money round(RoundingPolicy policy) {
		Objects.requireNonNull(policy, "policy");
		return new Money(policy.apply(amount));
	}

	public boolean isZero() {
		return amount.signum() == 0;
	}

	@Override
	public int compareTo(Money other) {
		return amount.compareTo(other.amount);
	}

}
