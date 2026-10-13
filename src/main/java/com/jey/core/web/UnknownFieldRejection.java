package com.jey.core.web;

import com.jey.core.shared.RejectUnknownFields;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.deser.DeserializationProblemHandler;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * {@link RejectUnknownFields}가 붙은 타입을 읽다가 모르는 필드를 만나면 읽기를 실패시킨다.
 * 붙지 않은 타입은 지금처럼 모르는 필드를 넘긴다(JSON을 읽는 다른 곳 — 이벤트, 저장해 둔 값 — 에 영향을 주지 않는다).
 *
 * <p>실패는 요청 형식 오류({@code COMMON_BAD_REQUEST})로 나간다. 모르는 필드의 이름은 사용자가 보낸 글자라 응답에 싣지 않는다
 * ({@code GlobalExceptionHandler}).
 */
@Configuration(proxyBeanMethods = false)
class UnknownFieldRejection {

	@Bean
	JsonMapperBuilderCustomizer rejectUnknownFieldsWhereAsked() {
		return builder -> builder.addHandler(new Handler());
	}

	static final class Handler extends DeserializationProblemHandler {

		@Override
		public boolean handleUnknownProperty(DeserializationContext ctxt, JsonParser p, ValueDeserializer<?> deserializer,
				Object beanOrClass, String propertyName) throws JacksonException {
			// 생성자로 만드는 타입(record)을 읽는 중에는 아직 객체가 없어 클래스가 넘어온다.
			Class<?> type = (beanOrClass instanceof Class<?> target) ? target : beanOrClass.getClass();
			if (type.isAnnotationPresent(RejectUnknownFields.class)) {
				throw UnrecognizedPropertyException.from(p, beanOrClass, propertyName, null);
			}
			// 처리하지 않았다고 답한다. 기본 동작(읽지 않고 넘김)이 이어진다.
			return false;
		}

	}

}
