package com.jey;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.jey.core.audit.api.AuditAction;
import com.jey.core.shared.DomainEvent;
import com.jey.core.shared.EventMetadata;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// 감사 로그의 행위 이름은 기록할 때에야 검사된다. 거부된 시도의 이름이 틀리면 그 거부가 실제로 일어난 날
// 409 대신 500이 처음 나온다. 선언된 행위를 모두 모아 미리 검사한다.
class AuditActionRulesTest {

	private static final JavaClasses MAIN = MainClasses.get();

	@Test
	void 행위는_enum으로_선언한다() {
		assertThat(actionTypes()).isNotEmpty().allMatch(JavaClass::isEnum);
	}

	@Test
	void 행위_이름은_형식에_맞고_결과가_정해져_있다() {
		assertThat(actions()).isNotEmpty().allSatisfy(action -> {
			assertThat(EventMetadata.isValidName(action.code())).as(action.code()).isTrue();
			assertThat(action.outcome()).as(action.code()).isNotNull();
		});
	}

	// 같은 이름을 두 행위가 쓰면 조회에서 섞인다.
	@Test
	void 행위_이름은_전체에서_겹치지_않는다() {
		List<String> codes = actions().stream().map(AuditAction::code).toList();

		assertThat(codes).contains("member.guardian-phone-duplicated", "member.student-updated");
		assertThat(duplicates(codes)).isEmpty();
	}

	// 이름의 앞부분이 선언한 모듈과 다르면 다른 모듈의 이름과 겹칠 수 있다.
	// core 아래의 기능은 하위 패키지 이름을 쓰고(com.jey.core.member → member), 그 밖은 모듈 이름을 쓴다.
	@Test
	void 행위_이름은_선언한_모듈의_이름으로_시작한다() {
		assertThat(actionTypes()).allSatisfy(type -> {
			String[] segments = type.getPackageName().split("\\.");
			String module = segments[2].equals("core") ? segments[3] : segments[2];
			for (Object constant : type.reflect().getEnumConstants()) {
				assertThat(((AuditAction) constant).code()).startsWith(module + ".");
			}
		});
	}

	// 이벤트 이름도 감사 로그에 행위 이름으로 저장된다. 직접 적는 행위와 이름이 같으면 한 사건이 두 종류로 보인다.
	@Test
	void 행위_이름은_이벤트_이름과_겹치지_않는다() throws IllegalAccessException {
		List<String> eventTypes = new ArrayList<>();
		for (JavaClass type : MAIN) {
			if (!type.isAssignableTo(DomainEvent.class) || type.isInterface()) {
				continue;
			}
			for (Field field : type.reflect().getDeclaredFields()) {
				if (field.getName().equals("TYPE") && Modifier.isStatic(field.getModifiers())) {
					eventTypes.add((String) field.get(null));
				}
			}
		}

		assertThat(eventTypes).contains("member.student-registered");
		assertThat(actions()).map(AuditAction::code).doesNotContainAnyElementsOf(eventTypes);
	}

	// 봉투의 생성자는 지점 ID와 계정 ID를 나란히 숫자로 받는다. 자리가 바뀌어도 컴파일된다.
	// 만드는 곳은 행위자를 타입으로 받는 create 하나로 한다.
	@Test
	void 이벤트_봉투는_create로만_만든다() {
		// 생성자를 부르는 것(new)과 가리키는 것(::new)을 모두 본다.
		ConstructorRules.constructedOnlyInside(EventMetadata.class).check(MAIN);
	}

	private static List<JavaClass> actionTypes() {
		return MAIN.stream().filter(type -> type.isAssignableTo(AuditAction.class) && !type.isInterface()).toList();
	}

	private static List<AuditAction> actions() {
		List<AuditAction> actions = new ArrayList<>();
		for (JavaClass type : actionTypes()) {
			Object[] constants = type.reflect().getEnumConstants();
			assertThat(constants).as(type.getName() + "은 enum이어야 한다").isNotNull();
			for (Object constant : constants) {
				actions.add((AuditAction) constant);
			}
		}
		return actions;
	}

	private static Set<String> duplicates(List<String> codes) {
		Set<String> seen = new HashSet<>();
		Set<String> duplicated = new HashSet<>();
		for (String code : codes) {
			if (!seen.add(code)) {
				duplicated.add(code);
			}
		}
		return duplicated;
	}

}
