package com.jey.core.shared;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyJsonTest {

	private final JsonMapper mapper = JsonMapper.builder().build();

	record Payload(Money amount) {
	}

	@Test
	void 금액은_JSON_숫자_하나로_나간다() {
		assertThat(mapper.writeValueAsString(Money.of(299250))).isEqualTo("299250");
		assertThat(mapper.writeValueAsString(new Payload(Money.of(299250)))).isEqualTo("{\"amount\":299250}");
	}

	@Test
	void 큰_금액도_지수_표기_없이_나간다() {
		assertThat(mapper.writeValueAsString(Money.of(new BigDecimal("3E+8")))).isEqualTo("300000000");
	}

	@Test
	void 소수_금액은_소수_그대로_나간다() {
		assertThat(mapper.writeValueAsString(Money.of(new BigDecimal("304593.75")))).isEqualTo("304593.75");
	}

	@Test
	void JSON_숫자를_금액으로_읽는다() {
		assertThat(mapper.readValue("299250", Money.class)).isEqualTo(Money.of(299250));
		assertThat(mapper.readValue("{\"amount\":304593.75}", Payload.class).amount())
				.isEqualTo(Money.of(new BigDecimal("304593.75")));
	}

	@Test
	void 음수_JSON은_금액으로_읽지_못한다() {
		assertThatThrownBy(() -> mapper.readValue("{\"amount\":-100}", Payload.class))
				.isInstanceOf(JacksonException.class);
	}

}
