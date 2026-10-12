package com.jey.core.web;

import java.util.LinkedHashMap;
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
 */
@Configuration
class NullableReferenceOpenApiConfig {

	private static final Set<String> NULL_ONLY = Set.of("null");

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
		Map<String, Schema> rewritten = new LinkedHashMap<>();
		properties.forEach((name, property) -> rewritten.put(name, isNullableReference(property) ? eitherOrNull(property) : property));
		schema.setProperties(rewritten);
	}

	private static boolean isNullableReference(Schema<?> property) {
		return property.get$ref() != null && NULL_ONLY.equals(property.getTypes());
	}

	// 설명은 바깥에 둔다. 가리키는 타입의 설명을 덮지 않고 이 필드의 설명으로 읽힌다.
	private static Schema<?> eitherOrNull(Schema<?> property) {
		Schema<?> nothing = new Schema<>();
		nothing.setTypes(NULL_ONLY);
		Schema<?> either = new Schema<>();
		either.setAnyOf(List.of(new Schema<>().$ref(property.get$ref()), nothing));
		either.setDescription(property.getDescription());
		return either;
	}

}
