package com.jey.core.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
		List<String> discountRates = List.of("0", "0.05", "0.07", "0.1", "0.15");
		for (long baseFee = 150_000; baseFee <= 450_000; baseFee += 1_000) {
			for (String discountRate : discountRates) {
				BigDecimal payRate = BigDecimal.ONE.subtract(new BigDecimal(discountRate));
				for (int lessons = 1; lessons <= 12; lessons++) {
					// 8로 나눈 값은 항상 유한소수라 정확히 나눌 수 있다.
					BigDecimal divideThenMultiply = BigDecimal.valueOf(baseFee).multiply(payRate)
							.divide(BigDecimal.valueOf(8))
							.multiply(BigDecimal.valueOf(lessons))
							.setScale(0, RoundingMode.DOWN);

					Money charge = Money.of(baseFee).times(payRate).times(lessons)
							.dividedBy(8, RoundingPolicy.ONE_WON_DOWN);

					assertThat(charge)
							.as("기준수강료 %d, 할인율 %s, %d회", baseFee, discountRate, lessons)
							.isEqualTo(Money.of(divideThenMultiply));
				}
			}
		}
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

	@Test
	void 이미_원_단위인_금액은_끊어도_그대로다() {
		assertThat(Money.of(299250).round(RoundingPolicy.ONE_WON_DOWN)).isEqualTo(Money.of(299250));
		assertThat(Money.of(304599).round(RoundingPolicy.ONE_WON_DOWN)).isEqualTo(Money.of(304599));
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

	@Test
	void 방향과_정책은_비울_수_없다() {
		assertThatNullPointerException().isThrownBy(() -> new RoundingPolicy(10, null)).withMessage("mode");
		assertThatNullPointerException().isThrownBy(() -> Money.of(100).round(null)).withMessage("policy");
		assertThatNullPointerException().isThrownBy(() -> Money.of(100).dividedBy(8, null)).withMessage("policy");
	}

}
