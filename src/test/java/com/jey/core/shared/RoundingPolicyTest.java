package com.jey.core.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class RoundingPolicyTest {

	private static final BigDecimal FIVE_PERCENT_OFF = BigDecimal.ONE.subtract(new BigDecimal("0.05"));

	@Test
	void 확정된_규칙은_소수점_버림이다() {
		assertThat(RoundingPolicy.ONE_WON_DOWN).isEqualTo(new RoundingPolicy(1, RoundingMode.DOWN));
	}

	// 기준수강료(8회분) × (1 − 할인율) ÷ 8 × 수업횟수. 납부 일지의 실제 금액과 같아야 한다.
	@Test
	void 검증값_280000원_5퍼센트_9회는_299250원() {
		Money charge = Money.of(280000).times(FIVE_PERCENT_OFF).times(9)
				.dividedBy(8, RoundingPolicy.ONE_WON_DOWN);

		assertThat(charge).isEqualTo(Money.of(299250));
	}

	// 285,000 × 0.95 ÷ 8 × 9 = 304,593.75
	@Test
	void 버림_예시_285000원_5퍼센트_9회는_304593원() {
		Money charge = Money.of(285000).times(FIVE_PERCENT_OFF).times(9)
				.dividedBy(8, RoundingPolicy.ONE_WON_DOWN);

		assertThat(charge).isEqualTo(Money.of(304593));
	}

	// 학원의 계산 순서는 "8로 나누고 횟수를 곱한 뒤 소수점 버림"이다. 코드는 곱셈을 먼저 하지만,
	// 중간에 끊지 않으면 두 순서의 값이 같다는 것을 실제 범위의 입력으로 확인한다.
	@Test
	void 곱셈을_먼저_해도_나누고_곱한_값과_같다() {
		// 0.075, 0.033은 할인 후 금액부터 소수가 되는 경우를 만들려고 넣었다.
		List<String> discountRates = List.of("0", "0.05", "0.07", "0.1", "0.15", "0.075", "0.033");
		List<Long> baseFees = new ArrayList<>(List.of(199_999L, 283_350L, 301_001L));
		for (long baseFee = 150_000; baseFee <= 450_000; baseFee += 1_000) {
			baseFees.add(baseFee);
		}
		int fractionalCases = 0;
		for (long baseFee : baseFees) {
			for (String discountRate : discountRates) {
				BigDecimal payRate = BigDecimal.ONE.subtract(new BigDecimal(discountRate));
				for (int lessons = 1; lessons <= 12; lessons++) {
					// 8 = 2³이라 몫이 항상 유한소수다. 그래서 끊는 방식 없이도 정확히 나눌 수 있다.
					BigDecimal exact = BigDecimal.valueOf(baseFee).multiply(payRate)
							.divide(BigDecimal.valueOf(8))
							.multiply(BigDecimal.valueOf(lessons));
					BigDecimal divideThenMultiply = exact.setScale(0, RoundingMode.DOWN);
					if (exact.compareTo(divideThenMultiply) != 0) {
						fractionalCases++;
					}

					Money charge = Money.of(baseFee).times(payRate).times(lessons)
							.dividedBy(8, RoundingPolicy.ONE_WON_DOWN);

					assertThat(charge)
							.as("기준수강료 %d, 할인율 %s, %d회", baseFee, discountRate, lessons)
							.isEqualTo(Money.of(divideThenMultiply));
				}
			}
		}
		// 버릴 소수가 있는 경우가 빠지면 이 테스트는 아무것도 확인하지 못한다.
		assertThat(fractionalCases).isGreaterThan(1_000);
	}

	// 학원 순서를 코드로 그대로 옮기면(나눠서 끊고 → 곱함) 회당 단가에서 버린 금액이 횟수만큼 불어난다.
	@Test
	void 나눠서_끊은_뒤에_곱하면_금액이_달라진다() {
		Money base = Money.of(285000).times(FIVE_PERCENT_OFF);

		Money wrong = base.dividedBy(8, RoundingPolicy.ONE_WON_DOWN).times(9);
		Money right = base.times(9).dividedBy(8, RoundingPolicy.ONE_WON_DOWN);

		assertThat(wrong).isEqualTo(Money.of(304587));
		assertThat(right).isEqualTo(Money.of(304593));
	}

	@Test
	void 나누어떨어지지_않아도_정확한_몫에서_버린다() {
		// 33.33… → 33
		assertThat(Money.of(100).dividedBy(3, RoundingPolicy.ONE_WON_DOWN)).isEqualTo(Money.of(33));
		// 666.66… → 666
		assertThat(Money.of(2000).dividedBy(3, RoundingPolicy.ONE_WON_DOWN)).isEqualTo(Money.of(666));
	}

	// 나눈 값을 중간에 자른 뒤 곱하면 100 ÷ 3 × 3 = 99.99… → 99원이 된다. 곱셈을 먼저 하면 정확히 100원이다.
	@Test
	void 곱셈을_먼저_하면_나눗셈_오차가_없다() {
		assertThat(Money.of(100).times(3).dividedBy(3, RoundingPolicy.ONE_WON_DOWN)).isEqualTo(Money.of(100));
	}

	@Test
	void 나누는_수는_양수여야_한다() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> Money.of(100).dividedBy(0, RoundingPolicy.ONE_WON_DOWN));
		assertThatIllegalArgumentException()
				.isThrownBy(() -> Money.of(100).dividedBy(-8, RoundingPolicy.ONE_WON_DOWN));
	}

	@Test
	void 나눌_때_단위와_방향을_바꿀_수_있다() {
		// 100 ÷ 8 = 12.5
		assertThat(Money.of(100).dividedBy(8, new RoundingPolicy(1, RoundingMode.DOWN))).isEqualTo(Money.of(12));
		assertThat(Money.of(100).dividedBy(8, new RoundingPolicy(1, RoundingMode.HALF_UP))).isEqualTo(Money.of(13));
		assertThat(Money.of(100).dividedBy(8, new RoundingPolicy(10, RoundingMode.UP))).isEqualTo(Money.of(20));
	}

	// 몫을 먼저 원 단위로 끊고 다시 10원 단위로 끊으면 144.5 → 145 → 150이 된다. 정확한 몫에서 한 번만 끊으면 140이다.
	@Test
	void 단위가_커도_정확한_몫에서_한_번만_끊는다() {
		assertThat(Money.of(1156).dividedBy(8, new RoundingPolicy(10, RoundingMode.HALF_UP))).isEqualTo(Money.of(140));
		assertThat(Money.of(2436750).dividedBy(8, new RoundingPolicy(10, RoundingMode.DOWN)))
				.isEqualTo(Money.of(304590));
	}

	@Test
	void 이미_원_단위인_금액은_끊어도_그대로다() {
		assertThat(Money.of(299250).round(RoundingPolicy.ONE_WON_DOWN)).isEqualTo(Money.of(299250));
		assertThat(Money.ZERO.round(RoundingPolicy.ONE_WON_DOWN)).isEqualTo(Money.ZERO);
	}

	@Test
	void 버림은_소수점이_커도_올리지_않는다() {
		assertThat(Money.of(new BigDecimal("304593.75")).round(RoundingPolicy.ONE_WON_DOWN))
				.isEqualTo(Money.of(304593));
		assertThat(Money.of(new BigDecimal("304593.999")).round(RoundingPolicy.ONE_WON_DOWN))
				.isEqualTo(Money.of(304593));
		assertThat(Money.of(new BigDecimal("0.99")).round(RoundingPolicy.ONE_WON_DOWN)).isEqualTo(Money.ZERO);
	}

	@Test
	void 끊을_때_단위와_방향을_바꿀_수_있다() {
		Money amount = Money.of(new BigDecimal("304595.5"));

		assertThat(amount.round(new RoundingPolicy(1, RoundingMode.HALF_UP))).isEqualTo(Money.of(304596));
		assertThat(amount.round(new RoundingPolicy(10, RoundingMode.DOWN))).isEqualTo(Money.of(304590));
		assertThat(amount.round(new RoundingPolicy(100, RoundingMode.DOWN))).isEqualTo(Money.of(304500));
	}

	@Test
	void 단위는_양수여야_한다() {
		assertThatIllegalArgumentException().isThrownBy(() -> new RoundingPolicy(0, RoundingMode.DOWN));
		assertThatIllegalArgumentException().isThrownBy(() -> new RoundingPolicy(-10, RoundingMode.DOWN));
	}

	// UNNECESSARY는 나누어떨어지지 않으면 예외를 던진다. 계산 예외로 청구가 막히면 안 되므로 만들 때 거부한다.
	@Test
	void 끊지_않는_방향은_쓸_수_없다() {
		assertThatIllegalArgumentException().isThrownBy(() -> new RoundingPolicy(1, RoundingMode.UNNECESSARY));
	}

	@Test
	void 방향과_정책은_비울_수_없다() {
		assertThatNullPointerException().isThrownBy(() -> new RoundingPolicy(10, null)).withMessage("mode");
		assertThatNullPointerException().isThrownBy(() -> Money.of(100).round(null)).withMessage("policy");
		assertThatNullPointerException().isThrownBy(() -> Money.of(100).dividedBy(8, null)).withMessage("policy");
	}

}
