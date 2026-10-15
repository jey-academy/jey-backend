package com.jey.core.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.DeserializationConfig;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.deser.ValueDeserializerModifier;
import tools.jackson.databind.deser.std.DelegatingDeserializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.type.LogicalType;

/**
 * 요청 본문을 API 명세의 타입 그대로만 읽는 JSON 설정 (ADR-0036).
 *
 * <p>앱 전체의 설정에서 파생해 만든다. 모듈, 값 객체의 읽기, 모르는 필드 거부 장치는 그대로 물려받고, 읽을 때 타입을 바꿔 주는 것만 막는다.
 * 쓰기 설정은 바꾸지 않는다(응답 모양은 그대로다). 앱 전체의 설정은 건드리지 않는다: 저장해 둔 이벤트를 되살리는 쪽이 그 설정을 쓰고,
 * 이 앱이 같은 설정으로 쓴 값이라 조일 이유가 없다. 거기서 읽기가 실패하면 다시 보내도 낫지 않는다.
 *
 * <p>막는 것: 숫자 칸의 문자열·소수·빈 문자열, 글자 칸의 숫자·참거짓, 참거짓 칸의 문자열·숫자, enum 칸의 번호·빈 문자열,
 * 날짜·시각 칸의 숫자·배열·빈 문자열, 금액 칸의 문자열·빈 문자열. 막지 않는 것: {@code null}, 글자 칸의 빈 문자열.
 * 막힌 값은 그 칸의 경로가 붙은 예외가 되어 그 칸의 오류로 답해진다({@code GlobalExceptionHandler}).
 *
 * <p>HTTP 메시지 변환기의 JSON 변환기를 이 설정으로 바꾼다. 요청 본문을 읽는 곳과 응답을 쓰는 곳이 모두 이 변환기다.
 * JSON이 아닌 본문 형식의 변환기(YAML)는 뺀다. 명세 도구가 끌어온 라이브러리 때문에 저절로 등록되는데, 엄격한 설정도 모르는 필드 거부도 거치지 않아
 * 이 규칙을 통째로 우회한다. 이 API는 JSON만 쓴다(그런 본문은 415다). API 명세의 YAML 문서는 명세 도구가 직접 쓰므로 그대로 나온다.
 */
@Configuration(proxyBeanMethods = false)
class StrictRequestJson implements WebMvcConfigurer {

	private final JsonMapper app;

	StrictRequestJson(JsonMapper app) {
		this.app = app;
	}

	@Override
	public void configureMessageConverters(HttpMessageConverters.ServerBuilder builder) {
		builder.withJsonConverter(new JacksonJsonHttpMessageConverter(strict(app)));
		builder.configureMessageConvertersList(converters -> converters.removeIf(StrictRequestJson::readsYaml));
	}

	// YAML을 이름으로 지원하는 변환기. 아무 형식이나 받는 변환기(*/* — 글자, 바이트)는 YAML과 "호환"으로 판정되므로 와일드카드는 보지 않는다.
	// 그것까지 빼면 글자·바이트를 쓰는 응답(API 명세 문서 등)이 깨진다.
	private static boolean readsYaml(HttpMessageConverter<?> converter) {
		return converter.getSupportedMediaTypes()
			.stream()
			.anyMatch(type -> !type.isWildcardType() && !type.isWildcardSubtype()
					&& type.isCompatibleWith(MediaType.APPLICATION_YAML));
	}

	static JsonMapper strict(JsonMapper app) {
		return app.rebuild()
			// "1" → 1, "true" → true 같은 글자에서의 변환
			.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
			// 1.9 → 1
			.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
			// 1, "1" → 선언 순서의 값
			.enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
			// "" → 값 없음(숫자, 날짜, enum, 금액). 글자 칸의 ""는 변환이 아니라서 걸리지 않는다
			.withCoercionConfigDefaults(config -> config.setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail))
			// 1, 1.5, true → "1", "1.5", "true"
			.withCoercionConfig(LogicalType.Textual,
					config -> config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
						.setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
						.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
			// 20261015 → 날 수로 해석한 날짜
			.withCoercionConfig(LogicalType.DateTime,
					config -> config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail))
			// [2026,10,15] → 날짜. 날짜 읽기가 배열을 자기 형식으로 받아서 강제 변환 설정으로는 막히지 않는다
			.addModule(new SimpleModule("strict-request-date-time").setDeserializerModifier(new DateTimeArrayRejection()))
			.build();
	}

	// java.time 타입을 읽는 부분을 감싸 배열을 막는다. 그 밖의 입력은 원래 읽기에 넘긴다.
	private static final class DateTimeArrayRejection extends ValueDeserializerModifier {

		@Override
		public ValueDeserializer<?> modifyDeserializer(DeserializationConfig config, BeanDescription.Supplier beanDesc,
				ValueDeserializer<?> deserializer) {
			if (beanDesc.getBeanClass().getPackageName().equals("java.time")) {
				return new ArrayRejectingDeserializer(deserializer);
			}
			return deserializer;
		}

	}

	private static final class ArrayRejectingDeserializer extends DelegatingDeserializer {

		ArrayRejectingDeserializer(ValueDeserializer<?> delegatee) {
			super(delegatee);
		}

		@Override
		protected ValueDeserializer<?> newDelegatingInstance(ValueDeserializer<?> newDelegatee) {
			return new ArrayRejectingDeserializer(newDelegatee);
		}

		@Override
		public Object deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
			if (p.hasToken(JsonToken.START_ARRAY)) {
				// 칸의 경로는 바깥에서 붙는다. 문구는 응답에 나가지 않는다(그 칸의 고정 문구로 바뀐다).
				return ctxt.reportInputMismatch(handledType(), "배열로 된 날짜·시각은 받지 않는다");
			}
			return super.deserialize(p, ctxt);
		}

	}

}
