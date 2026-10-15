package com.jey.core.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.jey.core.shared.Money;
import com.jey.core.shared.RejectUnknownFields;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 요청 본문은 API 명세의 타입 그대로만 읽는다 (ADR-0036). 앱 전체의 JSON 설정은 느슨한 채로 둔다.
// 스프링 없이 본다: 앱 설정 대신 모르는 필드 거부 장치만 붙인 설정에서 엄격한 복사본을 만든다.
class StrictRequestJsonTest {

	private static final JsonMapper APP = JsonMapper.builder().addHandler(new UnknownFieldRejection.Handler()).build();

	private static final JsonMapper STRICT = StrictRequestJson.strict(APP);

	enum Color {

		RED, GREEN

	}

	record Item(Integer page) {
	}

	record Loose(Long id, Integer page, String name, Boolean flag, Color color, LocalDate date, Money fee,
			List<Item> items) {
	}

	@RejectUnknownFields
	record Guarded(String name) {
	}

	// 보낸 본문 | 실패해야 하는 칸
	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', value = {
			// 숫자 칸: 문자열, 소수, 빈 문자열
			"{\"id\":\"1\"}|id", "{\"id\":1.9}|id", "{\"id\":1.0}|id", "{\"id\":\"\"}|id",
			// 범위를 넘는 숫자도 그 칸이다(목록 안이면 몇 번째인지까지)
			"{\"id\":99999999999999999999}|id", "{\"page\":99999999999}|page",
			"{\"items\":[{\"page\":1},{\"page\":99999999999}]}|items[1].page",
			// 글자 칸: 숫자, 참거짓
			"{\"name\":1}|name", "{\"name\":1.5}|name", "{\"name\":true}|name",
			// 참거짓 칸: 문자열, 숫자
			"{\"flag\":\"true\"}|flag", "{\"flag\":1}|flag",
			// enum 칸: 번호(숫자든 숫자 문자열이든), 빈 문자열
			"{\"color\":1}|color", "{\"color\":\"1\"}|color", "{\"color\":\"\"}|color",
			// 날짜 칸: 숫자, 배열, 빈 문자열
			"{\"date\":20261015}|date", "{\"date\":[2026,10,15]}|date", "{\"date\":\"\"}|date",
			// 금액 칸: 문자열, 빈 문자열
			"{\"fee\":\"1000\"}|fee", "{\"fee\":\"\"}|fee" })
	void 명세의_타입과_다른_값은_그_칸에서_읽기가_실패한다(String json, String field) {
		assertThatThrownBy(() -> STRICT.readValue(json, Loose.class)).isInstanceOfSatisfying(JacksonException.class,
				ex -> assertThat(fieldOf(ex)).isEqualTo(field));
	}

	@Test
	void 명세의_타입_그대로인_값은_읽는다() {
		Loose read = STRICT.readValue("""
				{"id":7,"page":2,"name":"햇살","flag":true,"color":"GREEN","date":"2026-10-15","fee":1000,
				 "items":[{"page":1}]}
				""", Loose.class);

		assertThat(read).isEqualTo(new Loose(7L, 2, "햇살", true, Color.GREEN, LocalDate.of(2026, 10, 15),
				Money.of(1000), List.of(new Item(1))));
	}

	// 값 없음과 빈 글자는 막지 않는다. 비울 수 없는 칸은 검증(@NotBlank)이 맡는다. 금액의 소수는 금액 규칙이 본다.
	@ParameterizedTest
	@ValueSource(strings = { "{}", "{\"id\":null,\"date\":null,\"fee\":null,\"color\":null}", "{\"name\":\"\"}",
			"{\"name\":\" \"}" })
	void 값_없음과_빈_글자는_읽는다(String json) {
		STRICT.readValue(json, Loose.class);
	}

	@Test
	void 금액의_소수는_금액_규칙에_맡긴다() {
		assertThat(STRICT.readValue("{\"fee\":1000.5}", Loose.class).fee()).isEqualTo(Money.of(new BigDecimal("1000.5")));
	}

	// 엄격한 설정은 앱 설정에서 파생한다. 앱 설정에 붙인 장치(모르는 필드 거부)를 그대로 물려받는다.
	@Test
	void 앱_설정에_붙인_장치를_물려받는다() {
		assertThatThrownBy(() -> STRICT.readValue("{\"name\":\"햇살\",\"nmae\":\"오타\"}", Guarded.class))
			.isInstanceOf(UnrecognizedPropertyException.class);
	}

	// 앱 전체의 설정은 그대로 느슨하다. 저장해 둔 이벤트를 되살리는 쪽이 이 설정을 쓴다.
	@Test
	void 앱_설정은_바뀌지_않는다() {
		Loose read = APP.readValue("{\"id\":\"1\",\"date\":[2026,10,15]}", Loose.class);

		assertThat(read.id()).isEqualTo(1L);
		assertThat(read.date()).isEqualTo(LocalDate.of(2026, 10, 15));
	}

	// 응답의 칸 이름과 같은 표기: items[1].page
	private static String fieldOf(JacksonException ex) {
		StringBuilder field = new StringBuilder();
		for (JacksonException.Reference step : ex.getPath()) {
			if (step.getPropertyName() != null) {
				if (!field.isEmpty()) {
					field.append('.');
				}
				field.append(step.getPropertyName());
			}
			else if (step.getIndex() >= 0) {
				field.append('[').append(step.getIndex()).append(']');
			}
		}
		return field.toString();
	}

}
