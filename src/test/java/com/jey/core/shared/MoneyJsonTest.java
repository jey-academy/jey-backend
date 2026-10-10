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
		assertThat(mapper.writeValueAsString(Money.ZERO)).isEqualTo("0");
	}

	@Test
	void 큰_금액도_지수_표기_없이_나간다() {
		assertThat(mapper.writeValueAsString(Money.of(new BigDecimal("3E+8")))).isEqualTo("300000000");
	}

	// 끊지 않은 금액도 그대로 나간다. 응답에 싣기 전에 끊는 것은 계산하는 쪽의 책임이다.
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

	// double을 거치면 0.1이 0.1000000000000000055…가 된다.
	@Test
	void 소수와_지수_표기를_오차_없이_읽는다() {
		assertThat(mapper.readValue("0.1", Money.class)).isEqualTo(Money.of(new BigDecimal("0.1")));
		assertThat(mapper.readValue("3E+5", Money.class)).isEqualTo(Money.of(300000));
		assertThat(mapper.readValue("0.00", Money.class)).isEqualTo(Money.ZERO);
		assertThat(mapper.readValue("-0.0", Money.class)).isEqualTo(Money.ZERO);
	}

	@Test
	void 숫자_모양_문자열도_금액으로_읽는다() {
		assertThat(mapper.readValue("{\"amount\":\"299250\"}", Payload.class).amount()).isEqualTo(Money.of(299250));
	}

	// null은 Money를 거치지 않는다. 필수 금액은 요청 DTO에서 @NotNull로 막아야 한다.
	@Test
	void null은_null로_읽힌다() {
		assertThat(mapper.readValue("{\"amount\":null}", Payload.class).amount()).isNull();
		assertThat(mapper.readValue("{}", Payload.class).amount()).isNull();
	}

	@Test
	void 숫자가_아닌_값은_금액으로_읽지_못한다() {
		assertThatThrownBy(() -> mapper.readValue("{\"amount\":\"abc\"}", Payload.class))
				.isInstanceOf(JacksonException.class);
		assertThatThrownBy(() -> mapper.readValue("{\"amount\":true}", Payload.class))
				.isInstanceOf(JacksonException.class);
	}

	@Test
	void 음수_JSON은_금액으로_읽지_못한다() {
		assertThatThrownBy(() -> mapper.readValue("{\"amount\":-100}", Payload.class))
				.isInstanceOf(JacksonException.class)
				.hasRootCauseInstanceOf(IllegalArgumentException.class);
	}

	// 13글자짜리 숫자가 서버 자원을 잡아먹지 않아야 한다. 풀어 쓰기 전에 거부되므로 바로 끝난다.
	@Test
	void 지수가_큰_JSON은_풀어_쓰지_않고_거부한다() {
		assertThatThrownBy(() -> mapper.readValue("{\"amount\":1E+999999999}", Payload.class))
				.isInstanceOf(JacksonException.class)
				.hasRootCauseInstanceOf(IllegalArgumentException.class);
	}

}
