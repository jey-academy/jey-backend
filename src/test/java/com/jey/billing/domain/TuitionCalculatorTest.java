package com.jey.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.jey.core.shared.Money;
import com.jey.core.shared.RoundingPolicy;

class TuitionCalculatorTest {

	private static final DiscountRate PASS_5 = new DiscountRate("DONGTAN_PASS", new BigDecimal("0.05"));

	private static final DiscountRate SIBLING_5 = new DiscountRate("SIBLING_5", new BigDecimal("0.05"));

	private static final DiscountRate SIBLING_7 = new DiscountRate("SIBLING_7", new BigDecimal("0.07"));

	@Test
	void 검증값_280000원_5퍼센트_9회는_299250원이다() {
		LineCharge line = TuitionCalculator.charge(Money.of(280_000), 9, List.of(SIBLING_5), DiscountPolicy.ADDITIVE);

		assertThat(line.chargeAmount()).isEqualTo(Money.of(299_250));
		assertThat(line.listAmount()).isEqualTo(Money.of(315_000));
		assertThat(line.discounts()).containsExactly(
				new AppliedDiscount("SIBLING_5", new BigDecimal("0.05"), Money.of(15_750)));
	}

	@Test
	void 검증값_285000원_5퍼센트_9회는_1원_미만을_버린_304593원이다() {
		LineCharge line = TuitionCalculator.charge(Money.of(285_000), 9, List.of(SIBLING_5), DiscountPolicy.ADDITIVE);

		assertThat(line.chargeAmount()).isEqualTo(Money.of(304_593));
		assertThat(line.listAmount()).isEqualTo(Money.of(320_625));
		assertThat(line.discounts()).extracting(AppliedDiscount::amount).containsExactly(Money.of(16_032));
	}

	// 회당 단가를 먼저 끊고 횟수를 곱하면 304,587원이 된다(버린 금액이 9배가 된다).
	@Test
	void 곱셈을_모두_끝낸_뒤_마지막에_한_번만_끊는다() {
		Money perLessonFirst = Money.of(285_000).times(new BigDecimal("0.95")).dividedBy(8, RoundingPolicy.ONE_WON_DOWN)
			.times(9);

		LineCharge line = TuitionCalculator.charge(Money.of(285_000), 9, List.of(SIBLING_5), DiscountPolicy.ADDITIVE);

		assertThat(perLessonFirst).isEqualTo(Money.of(304_587));
		assertThat(line.chargeAmount()).isEqualTo(Money.of(304_593));
	}

	@ParameterizedTest
	@EnumSource(DiscountPolicy.class)
	void 할인이_없으면_정가다(DiscountPolicy policy) {
		LineCharge line = TuitionCalculator.charge(Money.of(280_000), 8, List.of(), policy);

		assertThat(line.chargeAmount()).isEqualTo(Money.of(280_000));
		assertThat(line.listAmount()).isEqualTo(Money.of(280_000));
		assertThat(line.discounts()).isEmpty();
	}

	@Test
	void 합산은_할인율을_더해_한_번에_뺀다() {
		LineCharge line = TuitionCalculator.charge(Money.of(280_000), 8, List.of(PASS_5, SIBLING_5),
				DiscountPolicy.ADDITIVE);

		assertThat(line.chargeAmount()).isEqualTo(Money.of(252_000));
		assertThat(line.discounts()).extracting(AppliedDiscount::amount)
			.containsExactly(Money.of(14_000), Money.of(14_000));
	}

	@Test
	void 순차는_앞의_할인을_뺀_금액에서_다음_할인을_뺀다() {
		LineCharge line = TuitionCalculator.charge(Money.of(280_000), 8, List.of(PASS_5, SIBLING_5),
				DiscountPolicy.SEQUENTIAL);

		assertThat(line.chargeAmount()).isEqualTo(Money.of(252_700));
		assertThat(line.discounts()).extracting(AppliedDiscount::amount)
			.containsExactly(Money.of(14_000), Money.of(13_300));
	}

	@Test
	void 하나만은_할인_하나를_받는다() {
		LineCharge line = TuitionCalculator.charge(Money.of(280_000), 8, List.of(PASS_5), DiscountPolicy.SINGLE);

		assertThat(line.chargeAmount()).isEqualTo(Money.of(266_000));
	}

	// 어느 것을 남길지 앱이 고르지 않는다(할인은 직원이 고른다).
	@Test
	void 하나만에_할인을_둘_고르면_거부한다() {
		assertThatThrownBy(() -> TuitionCalculator.charge(Money.of(280_000), 8, List.of(PASS_5, SIBLING_5),
				DiscountPolicy.SINGLE))
			.isInstanceOf(TooManyDiscountsException.class);
	}

	// 소수가 생기는 조합에서도 할인 금액을 더하면 정가와 청구액의 차이와 1원도 다르지 않다.
	@ParameterizedTest
	@EnumSource(value = DiscountPolicy.class, names = { "ADDITIVE", "SEQUENTIAL" })
	void 할인_금액의_합은_정가와_청구액의_차이다(DiscountPolicy policy) {
		LineCharge line = TuitionCalculator.charge(Money.of(285_000), 7, List.of(SIBLING_5, SIBLING_7), policy);

		Money discounted = line.discounts().stream().map(AppliedDiscount::amount).reduce(Money.ZERO, Money::plus);
		assertThat(line.chargeAmount().plus(discounted)).isEqualTo(line.listAmount());
		assertThat(line.chargeAmount().isWholeWon()).isTrue();
		assertThat(line.listAmount().isWholeWon()).isTrue();
		assertThat(line.discounts()).allSatisfy(discount -> assertThat(discount.amount().isWholeWon()).isTrue());
	}

	@Test
	void 합산_할인율이_1_이상이면_거부한다() {
		DiscountRate half = new DiscountRate("HALF", new BigDecimal("0.5"));
		DiscountRate otherHalf = new DiscountRate("OTHER_HALF", new BigDecimal("0.5"));

		assertThatThrownBy(() -> TuitionCalculator.charge(Money.of(280_000), 8, List.of(half, otherHalf),
				DiscountPolicy.ADDITIVE))
			.isInstanceOf(DiscountTooLargeException.class);
		// 순차는 0.5 × 0.5만 남아 0원이 되지 않는다.
		assertThat(TuitionCalculator.charge(Money.of(280_000), 8, List.of(half, otherHalf), DiscountPolicy.SEQUENTIAL)
			.chargeAmount()).isEqualTo(Money.of(70_000));
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, -1, 32 })
	void 수업_횟수는_1회부터_31회까지다(int lessonCount) {
		assertThatThrownBy(() -> TuitionCalculator.charge(Money.of(280_000), lessonCount, List.of(),
				DiscountPolicy.ADDITIVE))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 수업_횟수의_양끝은_받는다() {
		assertThat(TuitionCalculator.charge(Money.of(280_000), 1, List.of(), DiscountPolicy.ADDITIVE).chargeAmount())
			.isEqualTo(Money.of(35_000));
		assertThat(TuitionCalculator.charge(Money.of(280_000), 31, List.of(), DiscountPolicy.ADDITIVE).chargeAmount())
			.isEqualTo(Money.of(1_085_000));
	}

	@Test
	void 기준_수강료는_0원보다_크고_1원_단위다() {
		assertThatThrownBy(() -> TuitionCalculator.charge(Money.ZERO, 8, List.of(), DiscountPolicy.ADDITIVE))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> TuitionCalculator.charge(Money.of(new BigDecimal("280000.5")), 8, List.of(),
				DiscountPolicy.ADDITIVE))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 같은_할인을_두_번_받지_않는다() {
		assertThatThrownBy(() -> TuitionCalculator.charge(Money.of(280_000), 8, List.of(SIBLING_5, SIBLING_5),
				DiscountPolicy.SEQUENTIAL))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = { "0", "1", "1.2", "-0.05", "0.12345" })
	void 할인율은_0보다_크고_1보다_작고_소수_넷째_자리까지다(String rate) {
		assertThatThrownBy(() -> new DiscountRate("X", new BigDecimal(rate)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 할인_코드는_비울_수_없다() {
		assertThatThrownBy(() -> new DiscountRate(" ", new BigDecimal("0.05")))
			.isInstanceOf(IllegalArgumentException.class);
	}

}
