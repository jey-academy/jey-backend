package com.jey;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.jey.core.notification.api.NotificationType;
import com.jey.core.notification.typerules.SampleNotificationTypes;
import com.jey.core.shared.EventMetadata;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// 알림 종류의 이름은 요청을 만들 때에야 검사된다. 이름이 틀리면 그 알림이 처음 나가야 하는 날 리스너가 실패한다.
// 이름은 보내는 쪽의 문구와 이어지고 같은 알림인지 가리는 데 쓰이므로, 두 종류가 같은 이름을 쓰면 섞인다.
// 선언된 종류를 모두 모아 미리 검사한다 (ADR-0029).
class NotificationTypeRulesTest {

	private static final JavaClasses MAIN = new ClassFileImporter()
			.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages("com.jey");

	// 운영 코드에는 아직 알림 종류가 없다(처음 쓰는 곳은 패스 발급이다). 그래서 지금은 "있으면 지킨다"만 본다.
	// 규칙이 위반을 실제로 잡는지는 아래의 본보기로 본다. 첫 종류가 생기면 여기에 "하나 이상 있다"를 더한다.
	@Test
	void 운영_코드의_알림_종류는_규칙을_지킨다() {
		assertThat(violations(declaredTypes())).isEmpty();
	}

	@Test
	void 규칙을_지킨_종류에는_위반이_없다() {
		assertThat(violations(List.of(SampleNotificationTypes.Valid.class))).isEmpty();
	}

	@Test
	void enum이_아닌_종류를_잡는다() {
		assertThat(violations(List.of(SampleNotificationTypes.NotEnum.class))).singleElement()
			.satisfies(violation -> assertThat(violation).contains("NotEnum").contains("enum으로 선언"));
	}

	@Test
	void 형식에_맞지_않는_이름을_잡는다() {
		assertThat(violations(List.of(SampleNotificationTypes.BadFormat.class))).singleElement()
			.satisfies(violation -> assertThat(violation).contains("notification.Sample_Sent").contains("형식"));
	}

	// core 아래의 기능은 하위 패키지 이름을 쓰고(com.jey.core.notification → notification), 그 밖은 모듈 이름을 쓴다.
	@Test
	void 선언한_모듈의_이름으로_시작하지_않는_이름을_잡는다() {
		assertThat(violations(List.of(SampleNotificationTypes.WrongModule.class))).singleElement()
			.satisfies(violation -> assertThat(violation).contains("billing.sample-sent").contains("notification."));
	}

	@Test
	void 다른_종류가_이미_쓴_이름을_잡는다() {
		assertThat(violations(List.of(SampleNotificationTypes.Valid.class, SampleNotificationTypes.Duplicated.class)))
			.singleElement()
			.satisfies(violation -> assertThat(violation).contains("notification.sample-sent").contains("겹친다"));
	}

	private static List<Class<?>> declaredTypes() {
		List<Class<?>> types = new ArrayList<>();
		for (JavaClass type : MAIN) {
			if (type.isAssignableTo(NotificationType.class) && !type.isInterface()) {
				types.add(type.reflect());
			}
		}
		return types;
	}

	// 규칙을 어긴 곳마다 한 줄. 비어 있으면 모두 지킨 것이다.
	private static List<String> violations(List<Class<?>> types) {
		List<String> violations = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (Class<?> type : types) {
			if (!type.isEnum()) {
				violations.add(type.getName() + ": 알림 종류는 enum으로 선언한다");
				continue;
			}
			String prefix = moduleOf(type) + ".";
			for (Object constant : type.getEnumConstants()) {
				String code = ((NotificationType) constant).code();
				String where = type.getName() + "." + constant + "(" + code + ")";
				if (!EventMetadata.isValidName(code)) {
					violations.add(where + ": 이름이 {모듈}.{사건} 형식의 소문자 kebab-case가 아니다");
				}
				else if (!code.startsWith(prefix)) {
					violations.add(where + ": 이름이 선언한 모듈의 이름(" + prefix + ")으로 시작하지 않는다");
				}
				if (!seen.add(code)) {
					violations.add(where + ": 다른 종류와 이름이 겹친다");
				}
			}
		}
		return violations;
	}

	private static String moduleOf(Class<?> type) {
		String[] segments = type.getPackageName().split("\\.");
		return segments[2].equals("core") ? segments[3] : segments[2];
	}

}
