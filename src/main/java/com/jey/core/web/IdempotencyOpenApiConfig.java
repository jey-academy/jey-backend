package com.jey.core.web;

import com.jey.core.shared.Idempotent;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API 명세에 {@code Idempotency-Key} 헤더를 싣는다(ADR-0022, ADR-0027).
 * 헤더는 필터가 읽으므로 컨트롤러 메서드의 인자에 없다. 명세에 따로 넣지 않으면 프론트의 생성 타입에 헤더가 없다.
 */
@Configuration
class IdempotencyOpenApiConfig {

	@Bean
	OperationCustomizer idempotencyKeyHeader() {
		return (operation, handlerMethod) -> {
			if (handlerMethod.hasMethodAnnotation(Idempotent.class)) {
				operation.addParametersItem(new HeaderParameter().name(IdempotencyFilter.KEY_HEADER)
					.required(true)
					.description("이번 시도의 키(UUID). 같은 시도를 다시 보낼 때는 같은 키를, 값을 고쳐 새로 낼 때는 새 키를 쓴다. "
							+ "같은 키의 같은 요청은 한 번만 실행되고 처음의 응답이 다시 온다(응답 헤더 Idempotency-Replayed: true). "
							+ "없으면 400, 같은 키에 다른 내용이면 422, 처음 요청이 처리 중이면 409")
					.schema(new StringSchema().minLength(8).maxLength(128).pattern("^[A-Za-z0-9_-]{8,128}$"))
					.example("3f2b8c1e-5a7d-4e2f-9b1c-0d6a8e4f2c11"));
			}
			return operation;
		};
	}

}
