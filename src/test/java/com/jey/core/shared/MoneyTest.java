package com.jey.core.shared;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class MoneyTest {

	// 수정 전에는 이 값을 풀어 쓰느라 테스트가 멈추므로, 상한 검사가 숫자를 풀기 전에 일어나는지를 이 값으로 확인한다.
	private static final String HUGE_EXPONENT = "999999999";

	@Test
	void 금액이_같으면_표현이_달라도_같다() {
		Money a = Money.of(new BigDecimal("1000.0"));
		Money b = Money.of(1000);

		assertThat(a).isEqualTo(b);
		assertThat(a).hasSameHashCodeAs(b);
		assertThat(new HashSet<>(List.of(a, b))).hasSize(1);
	}

	@Test
	void 영은_표현이_달라도_같다() {
		Money zero = Money.of(new BigDecimal("0.00"));

		assertThat(zero).isEqualTo(Money.ZERO);
		assertThat(zero).hasSameHashCodeAs(Money.ZERO);
		assertThat(Money.of(new BigDecimal("-0.00"))).isEqualTo(Money.ZERO);
		assertThat(Money.of(new BigDecimal("0E+" + HUGE_EXPONENT))).isEqualTo(Money.ZERO);
		assertThat(Money.of(100).times(new BigDecimal("0.00"))).isEqualTo(Money.ZERO);
	}

	@Test
	void 큰_정수_금액도_지수_표기가_되지_않는다() {
		assertThat(Money.of(300000).amount()).isEqualTo(new BigDecimal("300000"));
		assertThat(Money.of(new BigDecimal("3E+5")).amount()).isEqualTo(new BigDecimal("300000"));
	}

	@Test
	void 소수_금액은_필요한_자리까지만_유지한다() {
		assertThat(Money.of(new BigDecimal("304593.7500")).amount()).isEqualTo(new BigDecimal("304593.75"));
	}

	@Test
	void 음수_금액은_만들_수_없다() {
		assertThatIllegalArgumentException().isThrownBy(() -> Money.of(-1));
		assertThatIllegalArgumentException().isThrownBy(() -> Money.of(new BigDecimal("-0.01")));
	}

	@Test
	void 금액_없이_만들_수_없다() {
		assertThatNullPointerException().isThrownBy(() -> Money.of(null)).withMessage("amount");
	}

	@Test
	void 정수부는_15자리까지만_허용한다() {
		assertThat(Money.of(999_999_999_999_999L).amount()).isEqualTo(new BigDecimal("999999999999999"));
		assertThatIllegalArgumentException().isThrownBy(() -> Money.of(1_000_000_000_000_000L));
		assertThatIllegalArgumentException().isThrownBy(() -> Money.of(new BigDecimal("1E+15")));
	}

	@Test
	void 소수는_10자리까지만_허용한다() {
		assertThat(Money.of(new BigDecimal("0.0000000001")).amount()).isEqualTo(new BigDecimal("0.0000000001"));
		assertThatIllegalArgumentException().isThrownBy(() -> Money.of(new BigDecimal("0.00000000001")));
	}

	// 1E+999999999는 13글자지만 풀어 쓰면 0이 10억 개다. 풀기 전에 거부해야 하고, 예외 문구에도 풀어 쓰면 안 된다.
	@Test
	void 지수가_큰_입력은_풀어_쓰지_않고_거부한다() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> Money.of(new BigDecimal("1E+" + HUGE_EXPONENT)))
				.satisfies(ex -> assertThat(ex.getMessage()).hasSizeLessThan(200));
		assertThatIllegalArgumentException()
				.isThrownBy(() -> Money.of(new BigDecimal("-1E+" + HUGE_EXPONENT)))
				.satisfies(ex -> assertThat(ex.getMessage()).hasSizeLessThan(200));
		assertThatIllegalArgumentException()
				.isThrownBy(() -> Money.of(new BigDecimal("1E-" + HUGE_EXPONENT)))
				.satisfies(ex -> assertThat(ex.getMessage()).hasSizeLessThan(200));
		assertThatIllegalArgumentException()
				.isThrownBy(() -> Money.of(1).times(new BigDecimal("1E+" + HUGE_EXPONENT)));
	}

	@Test
	void 더하고_뺀다() {
		assertThat(Money.of(1000).plus(Money.of(250))).isEqualTo(Money.of(1250));
		assertThat(Money.of(1000).minus(Money.of(250))).isEqualTo(Money.of(750));
		assertThat(Money.of(1000).minus(Money.of(1000))).isEqualTo(Money.ZERO);
		assertThat(Money.of(new BigDecimal("304593.75")).plus(Money.of(new BigDecimal("0.25"))))
				.isEqualTo(Money.of(304594));
	}

	@Test
	void 빼서_음수가_되면_예외다() {
		assertThatIllegalArgumentException().isThrownBy(() -> Money.of(100).minus(Money.of(250)));
	}

	@Test
	void 더해서_상한을_넘으면_예외다() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> Money.of(999_999_999_999_999L).plus(Money.of(1)));
	}

	@Test
	void 정수와_비율을_곱한다() {
		assertThat(Money.of(35000).times(9)).isEqualTo(Money.of(315000));
		assertThat(Money.of(280000).times(new BigDecimal("0.95"))).isEqualTo(Money.of(266000));
		assertThat(Money.of(280000).times(0)).isEqualTo(Money.ZERO);
	}

	// Money는 스스로 끊지 않는다. 여기서 소수를 버리면 최종 금액이 1원 적어진다(1,069원 → 1,068원).
	@Test
	void 비율을_곱한_결과의_소수는_그대로_유지한다() {
		Money discounted = Money.of(1001).times(new BigDecimal("0.95"));

		assertThat(discounted).isEqualTo(Money.of(new BigDecimal("950.95")));
		// 950.95 × 9 ÷ 8 = 1,069.81875
		assertThat(discounted.times(9).dividedBy(8, RoundingPolicy.ONE_WON_DOWN)).isEqualTo(Money.of(1069));
	}

	// 0원에 음수를 곱하면 결과가 0이라 금액 검사로는 걸리지 않는다. 곱하는 수 자체를 검사한다.
	@Test
	void 음수를_곱하면_금액이_0원이어도_예외다() {
		assertThatIllegalArgumentException().isThrownBy(() -> Money.of(100).times(-1));
		assertThatIllegalArgumentException().isThrownBy(() -> Money.of(100).times(new BigDecimal("-0.5")));
		assertThatIllegalArgumentException().isThrownBy(() -> Money.ZERO.times(-1));
		assertThatIllegalArgumentException().isThrownBy(() -> Money.ZERO.times(new BigDecimal("-0.5")));
	}

	@Test
	void 연산_상대를_비울_수_없다() {
		assertThatNullPointerException().isThrownBy(() -> Money.of(100).plus(null));
		assertThatNullPointerException().isThrownBy(() -> Money.of(100).minus(null));
		assertThatNullPointerException().isThrownBy(() -> Money.of(100).times(null));
	}

	@Test
	void 크기를_비교한다() {
		assertThat(Money.of(100)).isLessThan(Money.of(200));
		assertThat(Money.of(new BigDecimal("100.0"))).isEqualByComparingTo(Money.of(100));
	}

	@Test
	void 영인지_확인한다() {
		assertThat(Money.ZERO.isZero()).isTrue();
		assertThat(Money.of(1).isZero()).isFalse();
		assertThat(Money.of(new BigDecimal("0.5")).isZero()).isFalse();
	}

	// 계산 중간값은 소수를 가질 수 있다. 저장하거나 응답에 싣기 전에 원 단위로 끊겼는지 확인하는 데 쓴다.
	@Test
	void 원_단위_금액인지_확인한다() {
		assertThat(Money.of(299250).isWholeWon()).isTrue();
		assertThat(Money.ZERO.isWholeWon()).isTrue();
		assertThat(Money.of(new BigDecimal("299250.00")).isWholeWon()).isTrue();

		Money fractional = Money.of(new BigDecimal("304593.75"));
		assertThat(fractional.isWholeWon()).isFalse();
		assertThat(fractional.round(RoundingPolicy.ONE_WON_DOWN).isWholeWon()).isTrue();
	}

}
