package com.jey.core.shared;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyConverterTest {

	private final MoneyConverter converter = new MoneyConverter();

	@Test
	void 금액을_숫자로_바꿔_저장한다() {
		assertThat(converter.convertToDatabaseColumn(Money.of(280_000))).isEqualByComparingTo("280000");
	}

	// DECIMAL(15, 0) 컬럼은 280000을 돌려주지만 드라이버에 따라 scale이 붙을 수 있다. 표현이 달라도 같은 금액이어야 한다.
	@Test
	void DB에서_읽은_숫자를_금액으로_바꾼다() {
		assertThat(converter.convertToEntityAttribute(new BigDecimal("280000"))).isEqualTo(Money.of(280_000));
		assertThat(converter.convertToEntityAttribute(new BigDecimal("280000.00"))).isEqualTo(Money.of(280_000));
	}

	@Test
	void 값이_없으면_없는_그대로_둔다() {
		assertThat(converter.convertToDatabaseColumn(null)).isNull();
		assertThat(converter.convertToEntityAttribute(null)).isNull();
	}

}
