package com.jey;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * "이 타입은 팩터리로만 만든다"를 보는 규칙. record는 생성자를 감출 수 없어서, 생성자를 쓰는 코드를 규칙 테스트로 막는다
 * ({@code EventMetadata.create}, {@code NotificationRequest.causedBy}, {@code RateLimit.of}).
 *
 * <p>생성자를 부르는 것({@code new X(…)})뿐 아니라 가리키는 것({@code X::new})도 본다.
 * 부르는 것만 보면 가리키는 쪽이 빠져나간다(ArchUnit은 둘을 따로 다룬다).
 * 리플렉션으로 만드는 것과, JSON·설정을 그 타입으로 바로 읽어 들이는 것은 보지 못한다.
 */
final class ConstructorRules {

	private ConstructorRules() {
	}

	/** {@code type} 밖의 코드는 {@code type}의 생성자를 부르지도 가리키지도 않는다. */
	static ArchRule constructedOnlyInside(Class<?> type) {
		return noClasses().that()
			.doNotHaveFullyQualifiedName(type.getName())
			.should()
			.accessTargetWhere(DescribedPredicate.describe(type.getSimpleName() + "의 생성자",
					(JavaAccess<?> access) -> access.getTargetOwner().isEquivalentTo(type)
							&& access.getName().equals("<init>")));
	}

}
