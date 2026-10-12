package com.jey.core.web;

import java.util.Set;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

// 명세 파일 전체는 ApiSpecTest가 본다. 여기서는 어떤 필드를 고쳐 적고 어떤 필드를 그대로 두는지를 본다.
class NullableReferenceOpenApiConfigTest {

	private static final String CONTACT = "#/components/schemas/ContactResponse";

	private final NullableReferenceOpenApiConfig config = new NullableReferenceOpenApiConfig();

	// "$ref"와 "type": "null"을 나란히 적으면 "그 타입이면서 null"이라 뜻이 맞지 않고, 프론트의 타입 생성기는 null을 버린다.
	@Test
	void 다른_타입을_가리키면서_null일_수_있는_필드는_둘_중_하나로_적는다() {
		Schema<?> nullableReference = new Schema<>().$ref(CONTACT).description("대표 연락처");
		nullableReference.setTypes(Set.of("null"));
		Schema<?> student = new Schema<>().addProperty("primaryContact", nullableReference);

		customize(student);

		Schema<?> rewritten = (Schema<?>) student.getProperties().get("primaryContact");
		assertThat(rewritten.get$ref()).isNull();
		assertThat(rewritten.getTypes()).isNull();
		assertThat(rewritten.getAnyOf()).hasSize(2);
		assertThat(rewritten.getAnyOf().get(0).get$ref()).isEqualTo(CONTACT);
		assertThat(rewritten.getAnyOf().get(1).getTypes()).containsExactly("null");
	}

	// 고쳐 적으면서 그 필드의 설명과 예시를 버리지 않는다.
	@Test
	void 고쳐_적어도_그_필드의_다른_속성은_남는다() {
		Schema<?> nullableReference = new Schema<>().$ref(CONTACT).description("대표 연락처").deprecated(true);
		nullableReference.setTypes(Set.of("null"));
		nullableReference.setExample("예시");
		Schema<?> student = new Schema<>().addProperty("primaryContact", nullableReference);

		customize(student);

		Schema<?> rewritten = (Schema<?>) student.getProperties().get("primaryContact");
		assertThat(rewritten.getDescription()).isEqualTo("대표 연락처");
		assertThat(rewritten.getDeprecated()).isTrue();
		assertThat(rewritten.getExample()).isEqualTo("예시");
	}

	// 뒤에서 도는 다른 설정이 이 필드를 더 고칠 수 있어야 한다.
	@Test
	void 고쳐_적은_필드는_더_고칠_수_있다() {
		Schema<?> nullableReference = new Schema<>().$ref(CONTACT);
		nullableReference.setTypes(Set.of("null"));
		Schema<?> student = new Schema<>().addProperty("primaryContact", nullableReference);
		customize(student);
		Schema<?> rewritten = (Schema<?>) student.getProperties().get("primaryContact");

		assertThatCode(() -> {
			rewritten.getAnyOf().add(new Schema<>());
			rewritten.getAnyOf().get(1).addType("string");
		}).doesNotThrowAnyException();
	}

	// 두 번 돌아도 결과가 같다.
	@Test
	void 이미_고쳐_적은_필드는_다시_건드리지_않는다() {
		Schema<?> nullableReference = new Schema<>().$ref(CONTACT);
		nullableReference.setTypes(Set.of("null"));
		Schema<?> student = new Schema<>().addProperty("primaryContact", nullableReference);

		customize(student);
		customize(student);

		assertThat(((Schema<?>) student.getProperties().get("primaryContact")).getAnyOf()).hasSize(2);
	}

	@Test
	void 그_밖의_필드는_그대로_둔다() {
		Schema<?> reference = new Schema<>().$ref(CONTACT);
		Schema<?> nullableNumber = new Schema<>();
		nullableNumber.setTypes(Set.of("integer", "null"));
		Schema<?> text = new Schema<>();
		text.setTypes(Set.of("string"));
		Schema<?> student = new Schema<>().addProperty("contact", reference)
			.addProperty("campusId", nullableNumber)
			.addProperty("name", text);

		customize(student);

		assertThat(student.getProperties().get("contact")).isSameAs(reference);
		assertThat(student.getProperties().get("campusId")).isSameAs(nullableNumber);
		assertThat(student.getProperties().get("name")).isSameAs(text);
		assertThat(student.getProperties().keySet()).containsExactly("contact", "campusId", "name");
	}

	@Test
	void 타입이나_필드가_없는_명세도_그대로_지나간다() {
		assertThatCode(() -> config.nullableReferences().customise(new OpenAPI())).doesNotThrowAnyException();
		assertThatCode(() -> config.nullableReferences().customise(new OpenAPI().components(new Components())))
			.doesNotThrowAnyException();
		assertThatCode(() -> customize(new Schema<>())).doesNotThrowAnyException();
	}

	private void customize(Schema<?> schema) {
		config.nullableReferences().customise(new OpenAPI().components(new Components().addSchemas("Student", schema)));
	}

}
