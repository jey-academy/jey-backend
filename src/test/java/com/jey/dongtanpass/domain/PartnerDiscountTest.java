package com.jey.dongtanpass.domain;

import java.math.BigDecimal;

import com.jey.core.shared.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

// 연계 학원이 받을 금액 = 원래 수강료(첫 달 한 달 치) × (1 − 할인율), 1원 미만 버림. 할인액은 그 차이다.
class PartnerDiscountTest {

	private static final BigDecimal FIVE_PERCENT = new BigDecimal("0.05");

	@Test
	void 원래_수강료_280000원에_5퍼센트면_받을_금액은_266000원이고_할인액은_14000원이다() {
		PartnerDiscount discount = PartnerDiscount.of(Money.of(280000), FIVE_PERCENT);

		assertThat(discount.originalFee()).isEqualTo(Money.of(280000));
		assertThat(discount.discountRate()).isEqualByComparingTo("0.05");
		assertThat(discount.payableAmount()).isEqualTo(Money.of(266000));
		assertThat(discount.discountAmount()).isEqualTo(Money.of(14000));
	}

	// 285,555 × 0.95 = 271,277.25 → 받을 금액은 1원 미만을 버려 271,277원. 할인액은 나머지라 둘을 더하면 원래 수강료다.
	@Test
	void 받을_금액에서_1원_미만을_버리고_할인액은_나머지다() {
		PartnerDiscount discount = PartnerDiscount.of(Money.of(285555), FIVE_PERCENT);

		assertThat(discount.payableAmount()).isEqualTo(Money.of(271277));
		assertThat(discount.discountAmount()).isEqualTo(Money.of(14278));
		assertThat(discount.payableAmount().plus(discount.discountAmount())).isEqualTo(Money.of(285555));
	}

	// 할인율은 패스에 남은 발급 때 값을 넘겨받는다(소수 넷째 자리까지).
	@Test
	void 넘겨받은_할인율로_계산한다() {
		PartnerDiscount discount = PartnerDiscount.of(Money.of(300000), new BigDecimal("0.0725"));

		assertThat(discount.payableAmount()).isEqualTo(Money.of(278250));
		assertThat(discount.discountAmount()).isEqualTo(Money.of(21750));
	}

	@Test
	void 상한까지는_받는다() {
		assertThat(PartnerDiscount.of(PartnerDiscount.MAX_ORIGINAL_FEE, FIVE_PERCENT).payableAmount())
			.isEqualTo(Money.of(9_500_000));
		assertThat(PartnerDiscount.MAX_ORIGINAL_FEE).isEqualTo(Money.of(10_000_000));
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "0", "0.5", "10000001" })
	void 원래_수강료가_0원이거나_1원_단위가_아니거나_상한을_넘으면_거부한다(String fee) {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> PartnerDiscount.of(Money.of(new BigDecimal(fee)), FIVE_PERCENT));
	}

	@Test
	void 빈_값은_거부한다() {
		assertThatIllegalArgumentException().isThrownBy(() -> PartnerDiscount.of(null, FIVE_PERCENT));
		assertThatIllegalArgumentException().isThrownBy(() -> PartnerDiscount.of(Money.of(280000), null));
	}

}
