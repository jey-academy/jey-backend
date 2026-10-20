package com.jey;

import com.jey.samplerules.CallsConstructor;
import com.jey.samplerules.Guarded;
import com.jey.samplerules.ReferencesConstructor;
import com.jey.samplerules.UsesFactory;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

// "팩터리로만 만든다"는 규칙(ConstructorRules)이 실제로 잡는지를 본보기로 본다.
// 이 규칙을 쓰는 곳: AuditActionRulesTest(EventMetadata), NotificationTypeRulesTest(NotificationRequest),
// RateLimitRulesTest(RateLimit), DongtanpassRulesTest(PartnerDiscount).
class ConstructorRulesTest {

	private static final ArchRule RULE = ConstructorRules.constructedOnlyInside(Guarded.class);

	@Test
	void 생성자를_직접_부르는_코드를_잡는다() {
		var result = RULE.evaluate(classesOf(CallsConstructor.class));

		assertThat(result.hasViolation()).isTrue();
		assertThat(result.getFailureReport().getDetails()).singleElement()
			.satisfies(detail -> assertThat(detail).contains("CallsConstructor").contains("Guarded"));
	}

	@Test
	void 생성자를_가리키는_코드를_잡는다() {
		var result = RULE.evaluate(classesOf(ReferencesConstructor.class));

		assertThat(result.hasViolation()).isTrue();
		assertThat(result.getFailureReport().getDetails())
			.anySatisfy(detail -> assertThat(detail).contains("ReferencesConstructor").contains("Guarded"));
	}

	// 처음에는 부르는 것만 보는 조건(callConstructorWhere)을 썼다. 그 조건은 가리키는 코드를 놓친다.
	// 누가 규칙을 그쪽으로 되돌리면 위의 테스트가 실패하는데, 왜 실패하는지를 여기에 남겨 둔다.
	@Test
	void 부르는_것만_보는_조건은_가리키는_코드를_놓친다() {
		ArchRule callsOnly = noClasses().that()
			.doNotHaveFullyQualifiedName(Guarded.class.getName())
			.should()
			.callConstructorWhere(DescribedPredicate.describe("Guarded의 생성자 호출",
					call -> call.getTargetOwner().isEquivalentTo(Guarded.class)));

		assertThat(callsOnly.evaluate(classesOf(CallsConstructor.class)).hasViolation()).isTrue();
		assertThat(callsOnly.evaluate(classesOf(ReferencesConstructor.class)).hasViolation()).isFalse();
	}

	@Test
	void 팩터리를_쓰는_코드는_잡지_않는다() {
		assertThat(RULE.evaluate(classesOf(UsesFactory.class)).hasViolation()).isFalse();
	}

	// 그 타입 자신이 생성자를 부르거나 가리키는 것은 규칙의 대상이 아니다(팩터리가 그렇게 한다).
	// 그 타입만 넣으면 볼 클래스가 없어 규칙이 실패하므로, 지키는 본보기와 함께 넣는다.
	@Test
	void 그_타입_안에서_생성자를_쓰는_것은_잡지_않는다() {
		assertThat(RULE.evaluate(classesOf(Guarded.class, UsesFactory.class)).hasViolation()).isFalse();
	}

	private static JavaClasses classesOf(Class<?>... types) {
		return new ClassFileImporter().importClasses(types);
	}

}
