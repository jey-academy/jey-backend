package com.jey.core.shared;

import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhoneNumberJsonTest {

	private final JsonMapper mapper = JsonMapper.builder().build();

	record Payload(String name, PhoneNumber phone) {
	}

	@Test
	void JSON_문자열에서_전화번호를_읽는다() {
		Payload payload = mapper.readValue("{\"name\":\"김학부모\",\"phone\":\"010-1234-5678\"}", Payload.class);

		assertThat(payload.phone()).isEqualTo(PhoneNumber.of("01012345678"));
	}

	// 응답 타입에 전화번호를 그대로 넣어도 원문이 나가지 않는다. 전체 번호를 내보내는 기능은 value()를 직접 꺼내 써야 한다.
	@Test
	void JSON으로_내보내면_가린_표시가_나간다() {
		String json = mapper.writeValueAsString(new Payload("김학부모", PhoneNumber.of("010-1234-5678")));

		assertThat(json).isEqualTo("{\"name\":\"김학부모\",\"phone\":\"010-****-5678\"}");
	}

	@Test
	void 형식이_틀린_번호는_읽지_못한다() {
		assertThatThrownBy(() -> mapper.readValue("{\"phone\":\"02-123-4567\"}", Payload.class))
				.isInstanceOf(JacksonException.class);
		assertThatThrownBy(() -> mapper.readValue("{\"phone\":1012345678}", Payload.class))
				.isInstanceOf(JacksonException.class);
	}

	// 가린 값을 다시 받아 저장하면 번호가 깨진다. 화면이 받은 값을 그대로 돌려보내는 실수를 막는다.
	@Test
	void 가린_표시는_전화번호로_읽히지_않는다() {
		assertThatThrownBy(() -> mapper.readValue("{\"phone\":\"010-****-5678\"}", Payload.class))
				.isInstanceOf(JacksonException.class);
	}

	@Test
	void 값이_없으면_없는_그대로_읽는다() {
		assertThat(mapper.readValue("{\"name\":\"김학부모\",\"phone\":null}", Payload.class).phone()).isNull();
	}

}
