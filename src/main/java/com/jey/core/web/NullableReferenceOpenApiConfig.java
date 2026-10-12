package com.jey.core.web;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API 명세에서 "다른 타입을 가리키는데 null일 수도 있는 필드"를 바로 적는다(ADR-0022).
 *
 * <p>명세 생성기는 그런 필드를 {@code $ref}와 {@code "type": "null"}을 나란히 적는다. 이 둘은 "그 타입이면서 동시에 null"로 읽혀
 * 뜻이 맞지 않고, 프론트의 타입 생성기는 {@code $ref}만 보고 null을 버린다(null이 올 수 있는 필드가 타입에서는 항상 있는 것으로 나온다).
 * "그 타입이거나 null"({@code anyOf})로 바꿔 적어 프론트 타입에 {@code | null}이 나오게 한다.
 *
 * <p>고치는 자리는 응답·요청 타입의 바로 아래 필드뿐이다. 목록의 원소({@code items})나 필드 안에 다시 적힌 객체는 보지 않는다.
 * 그런 자리에 null일 수 있는 참조가 생기면 여기를 넓혀야 한다(넓히지 않으면 {@code ApiSpecTest}가 틀린 표현이 남았다고 알린다).
 */
@Configuration
class NullableReferenceOpenApiConfig {

	private static final String NULL = "null";

	@Bean
	OpenApiCustomizer nullableReferences() {
		return openApi -> {
			if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
				return;
			}
			openApi.getComponents().getSchemas().values().forEach(NullableReferenceOpenApiConfig::rewriteProperties);
		};
	}

	@SuppressWarnings("rawtypes")
	private static void rewriteProperties(Schema<?> schema) {
		Map<String, Schema> properties = schema.getProperties();
		if (properties == null) {
			return;
		}
		properties.values().stream().filter(NullableReferenceOpenApiConfig::isNullableReference).forEach(
				NullableReferenceOpenApiConfig::rewriteAsEitherOrNull);
	}

	private static boolean isNullableReference(Schema<?> property) {
		return property.get$ref() != null && Set.of(NULL).equals(property.getTypes());
	}

	// 그 필드의 스키마를 새로 만들지 않고 고쳐 쓴다. 설명, 예시 같은 나머지 속성이 그대로 남는다.
	// 뒤에서 도는 다른 설정이 더 고칠 수 있게 바꿀 수 있는 컬렉션을 넣는다.
	private static void rewriteAsEitherOrNull(Schema<?> property) {
		Schema<?> reference = new Schema<>().$ref(property.get$ref());
		Schema<?> nothing = new Schema<>();
		nothing.setTypes(new LinkedHashSet<>(Set.of(NULL)));
		property.set$ref(null);
		property.setTypes(null);
		property.setAnyOf(new ArrayList<>(List.of(reference, nothing)));
	}

}
