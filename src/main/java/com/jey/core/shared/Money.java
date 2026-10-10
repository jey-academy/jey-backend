package com.jey.core.shared;

import java.math.BigDecimal;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 금액(원). 0 이상, 정수부 15자리·소수 10자리까지만 허용한다.
 * 계산 중간값을 담을 수 있도록 1원 미만 소수도 허용한다. 원 단위로 맞추는 것은 {@link #dividedBy}나 {@link #round}를
 * 부르는 쪽의 책임이고, 끊겼는지는 {@link #isWholeWon()}으로 확인한다.
 * 금액을 끊는 일은 {@link RoundingPolicy}를 넘긴 곳에서만 일어난다.
 * JSON에서는 숫자 하나로 주고받는다({@code 299250}).
 */
public record Money(@JsonValue BigDecimal amount) implements Comparable<Money> {

	public static final Money ZERO = new Money(BigDecimal.ZERO);

	// JavaScript가 정확히 표현하는 정수(2^53, 16자리) 안쪽이라 JSON 숫자로 내보내도 값이 변하지 않는다.
	private static final int MAX_INTEGER_DIGITS = 15;

	private static final int MAX_FRACTION_DIGITS = 10;

	public Money {
		Objects.requireNonNull(amount, "amount");
		// 예외 문구에 금액을 풀어 쓰지 않는다. -1E+999999999 같은 값은 문구만으로 메모리를 다 쓴다.
		if (amount.signum() < 0) {
			throw new IllegalArgumentException("금액은 음수일 수 없다");
		}
		if (amount.signum() == 0) {
			amount = BigDecimal.ZERO;
		}
		else {
			// 자릿수는 숫자를 풀어 쓰지 않고도 알 수 있다. 1E+999999999처럼 짧은 입력이 거대한 숫자로 풀리기 전에 막는다.
			if ((long) amount.precision() - amount.scale() > MAX_INTEGER_DIGITS) {
				throw new IllegalArgumentException("금액이 너무 크다(정수부 " + MAX_INTEGER_DIGITS + "자리 초과)");
			}
			// BigDecimal의 equals는 1000.0과 1000을 다르게 본다(scale까지 비교). 금액이 같으면 같도록 표현을 하나로 맞춘다.
			amount = amount.stripTrailingZeros();
			if (amount.scale() > MAX_FRACTION_DIGITS) {
				throw new IllegalArgumentException("금액의 소수 자릿수가 너무 많다(" + MAX_FRACTION_DIGITS + "자리 초과)");
			}
			// stripTrailingZeros는 300000을 3E+5(scale −5)로 만든다. 지수 표기가 되지 않게 scale을 0으로 올린다.
			if (amount.scale() < 0) {
				amount = amount.setScale(0);
			}
		}
	}

	// 레코드는 기본이 속성 방식({"amount": …})이라, 숫자 하나에서 읽으려면 DELEGATING을 명시해야 한다.
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

	/**
	 * @throws IllegalArgumentException 곱하는 수가 음수일 때
	 */
	public Money times(long multiplier) {
		return times(BigDecimal.valueOf(multiplier));
	}

	/**
	 * 비율을 곱한다. 5% 할인은 {@code times(new BigDecimal("0.95"))}. 결과의 소수는 끊지 않고 그대로 둔다.
	 *
	 * @throws IllegalArgumentException 곱하는 수가 음수일 때
	 */
	public Money times(BigDecimal multiplier) {
		// 금액이 0원이면 결과도 0이라 금액 검사로는 걸리지 않는다. 곱하는 수를 직접 검사한다.
		if (multiplier.signum() < 0) {
			throw new IllegalArgumentException("곱하는 수는 음수일 수 없다");
		}
		return new Money(amount.multiply(multiplier));
	}

	/**
	 * 나누고 정책대로 끊는다. 몫을 중간 자릿수에서 자르지 않고 정확한 몫에서 한 번만 끊으므로,
	 * 나누어떨어지지 않아도 오차는 정책 단위 미만으로 한 번만 생긴다.
	 *
	 * <p>끊긴 금액에 다시 곱하면 그 오차가 배로 커진다. 곱셈을 모두 끝낸 뒤 마지막에 나눈다.
	 * <pre>
	 * fee.times(rate).times(9).dividedBy(8, policy)   // 304,593원 (맞음)
	 * fee.times(rate).dividedBy(8, policy).times(9)   // 304,587원 (회당 단가에서 버린 금액이 9배가 됨)
	 * </pre>
	 *
	 * @throws IllegalArgumentException 나누는 수가 0 이하일 때
	 */
	public Money dividedBy(long divisor, RoundingPolicy policy) {
		Objects.requireNonNull(policy, "policy");
		// 음수로 나누면 금액이 음수가 되므로 0뿐 아니라 음수도 막는다.
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

	/** 1원 미만 소수가 없는 금액인지. 계산 중간값은 소수를 가질 수 있으므로 저장하거나 응답에 싣기 전에 확인한다. */
	public boolean isWholeWon() {
		return amount.scale() == 0;
	}

	public boolean isZero() {
		return amount.signum() == 0;
	}

	@Override
	public int compareTo(Money other) {
		return amount.compareTo(other.amount);
	}

}
