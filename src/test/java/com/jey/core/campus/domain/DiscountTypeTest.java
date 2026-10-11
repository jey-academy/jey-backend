package com.jey.core.campus.domain;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class DiscountTypeTest {

	private static final BigDecimal FIVE_PERCENT = new BigDecimal("0.05");

	@Test
	void 할인_유형을_만들면_쓸_수_있는_상태다() {
		DiscountType created = DiscountType.create("SIBLING_5", "  형제 할인 5% ", FIVE_PERCENT);

		assertThat(created.getCode()).isEqualTo("SIBLING_5");
		assertThat(created.getName()).isEqualTo("형제 할인 5%");
		assertThat(created.getRate()).isEqualByComparingTo("0.05");
		assertThat(created.isActive()).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "sibling", "5_SIBLING", "SIBLING-5", "S", "형제", " SIBLING", "A2345678901234567890123456789012" })
	void 코드는_대문자로_시작하는_대문자_숫자_밑줄_2에서_30자다(String code) {
		assertThatIllegalArgumentException().isThrownBy(() -> DiscountType.create(code, "형제 할인", FIVE_PERCENT));
	}

	@Test
	void 코드가_없으면_만들_수_없다() {
		assertThatIllegalArgumentException().isThrownBy(() -> DiscountType.create(null, "형제 할인", FIVE_PERCENT));
	}

	@Test
	void 이름은_비울_수_없고_50자까지다() {
		assertThatIllegalArgumentException().isThrownBy(() -> DiscountType.create("CODE", " ", FIVE_PERCENT));
		assertThatIllegalArgumentException().isThrownBy(() -> DiscountType.create("CODE", null, FIVE_PERCENT));
		assertThatIllegalArgumentException()
				.isThrownBy(() -> DiscountType.create("CODE", "가".repeat(51), FIVE_PERCENT));
		assertThat(DiscountType.create("CODE", "가".repeat(50), FIVE_PERCENT).getName()).hasSize(50);
	}

	@ParameterizedTest
	@ValueSource(strings = { "0", "-0.05", "1.0001", "5" })
	void 할인율은_0보다_크고_1_이하다(String rate) {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> DiscountType.create("CODE", "할인", new BigDecimal(rate)));
	}

	// 5%를 5로 넣는 실수는 위에서 막힌다. 100% 할인(1)은 허용한다.
	@Test
	void 전액_할인은_만들_수_있다() {
		assertThat(DiscountType.create("FREE", "전액 할인", BigDecimal.ONE).getRate()).isEqualByComparingTo("1");
	}

	// 컬럼은 소수 넷째 자리까지다. 더 긴 값은 DB가 반올림해서 입력과 저장이 달라진다.
	@Test
	void 할인율은_소수_넷째_자리까지다() {
		assertThat(DiscountType.create("CODE", "할인", new BigDecimal("0.0725")).getRate())
				.isEqualByComparingTo("0.0725");
		assertThatIllegalArgumentException()
				.isThrownBy(() -> DiscountType.create("CODE", "할인", new BigDecimal("0.07255")));
		assertThat(DiscountType.create("CODE", "할인", new BigDecimal("0.050000")).getRate())
				.isEqualByComparingTo("0.05");
	}

	// 같은 할인율이 0.05와 0.0500으로 따로 보이지 않게 한다.
	@Test
	void 할인율은_뒤의_0을_떼고_돌려준다() {
		assertThat(DiscountType.create("CODE", "할인", new BigDecimal("0.0500")).getRate())
				.isEqualTo(new BigDecimal("0.05"));
		assertThat(DiscountType.create("CODE", "할인", new BigDecimal("1.0000")).getRate()).isEqualTo(BigDecimal.ONE);
	}

	@Test
	void 이름과_할인율과_사용_여부를_바꾸고_코드는_그대로다() {
		DiscountType discountType = DiscountType.create("SIBLING_5", "형제 할인", FIVE_PERCENT);

		discountType.update(" 형제 할인 7% ", new BigDecimal("0.07"), false);

		assertThat(discountType.getCode()).isEqualTo("SIBLING_5");
		assertThat(discountType.getName()).isEqualTo("형제 할인 7%");
		assertThat(discountType.getRate()).isEqualByComparingTo("0.07");
		assertThat(discountType.isActive()).isFalse();
	}

	@Test
	void 바꿀_값이_틀리면_아무것도_바뀌지_않는다() {
		DiscountType discountType = DiscountType.create("SIBLING_5", "형제 할인", FIVE_PERCENT);

		assertThatIllegalArgumentException().isThrownBy(() -> discountType.update("새 이름", BigDecimal.TEN, false));

		assertThat(discountType.getName()).isEqualTo("형제 할인");
		assertThat(discountType.isActive()).isTrue();
	}

}
