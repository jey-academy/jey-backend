package com.jey.core.shared;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class MoneyTest {

	@Test
	void 금액이_같으면_표현이_달라도_같다() {
		Money a = Money.of(new BigDecimal("1000.0"));
		Money b = Money.of(1000);

		assertThat(a).isEqualTo(b);
		assertThat(a).hasSameHashCodeAs(b);
		assertThat(new HashSet<>(List.of(a, b))).hasSize(1);
	}

	@Test
	void 큰_정수_금액도_지수_표기가_되지_않는다() {
		assertThat(Money.of(300000).amount().toString()).isEqualTo("300000");
		assertThat(Money.of(new BigDecimal("3E+5")).amount().toString()).isEqualTo("300000");
	}

	@Test
	void 소수_금액은_필요한_자리까지만_유지한다() {
		assertThat(Money.of(new BigDecimal("304593.7500")).amount().toString()).isEqualTo("304593.75");
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
	void 더하고_뺀다() {
		assertThat(Money.of(1000).plus(Money.of(250))).isEqualTo(Money.of(1250));
		assertThat(Money.of(1000).minus(Money.of(250))).isEqualTo(Money.of(750));
		assertThat(Money.of(1000).minus(Money.of(1000))).isEqualTo(Money.ZERO);
	}

	@Test
	void 빼서_음수가_되면_예외다() {
		assertThatIllegalArgumentException().isThrownBy(() -> Money.of(100).minus(Money.of(250)));
	}

	@Test
	void 정수와_비율을_곱한다() {
		assertThat(Money.of(35000).times(9)).isEqualTo(Money.of(315000));
		assertThat(Money.of(280000).times(new BigDecimal("0.95"))).isEqualTo(Money.of(266000));
		assertThat(Money.of(280000).times(0)).isEqualTo(Money.ZERO);
	}

	@Test
	void 음수를_곱하면_예외다() {
		assertThatIllegalArgumentException().isThrownBy(() -> Money.of(100).times(-1));
		assertThatIllegalArgumentException().isThrownBy(() -> Money.of(100).times(new BigDecimal("-0.5")));
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
		assertThat(Money.ZERO.isZero()).isTrue();
		assertThat(Money.of(1).isZero()).isFalse();
	}

}
