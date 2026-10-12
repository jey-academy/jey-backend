package com.jey;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.jey.core.notification.api.NotificationRequest;
import com.jey.core.notification.api.NotificationType;
import com.jey.core.notification.typerules.SampleNotificationTypes;
import com.jey.core.shared.EventMetadata;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

// 알림 종류의 이름은 요청을 만들 때에야 검사된다. 이름이 틀리면 그 알림이 처음 나가야 하는 날 리스너가 실패한다.
// 이름은 보내는 쪽의 문구와 이어지고 같은 알림인지 가리는 데 쓰이므로, 두 종류가 같은 이름을 쓰면 섞인다.
// 선언된 종류를 모두 모아 미리 검사한다 (ADR-0029).
// 람다로 만든 종류는 클래스 파일이 없어 여기서 보이지 않는다. 그것은 요청을 만들 때 막는다(NotificationRequest).
class NotificationTypeRulesTest {

	private static final JavaClasses MAIN = new ClassFileImporter()
			.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages("com.jey");

	// 규칙이 위반을 잡는지 보기 위한 본보기들(테스트 소스).
	private static final JavaClasses SAMPLES = new ClassFileImporter()
			.importPackages(SampleNotificationTypes.class.getPackageName());

	// 운영 코드에는 아직 알림 종류가 없다(처음 쓰는 곳은 패스 발급이다). 그래서 지금은 "있으면 지킨다"만 본다.
	// 종류를 찾아내는 코드와 규칙이 실제로 동작하는지는 아래의 본보기로 본다.
	// 첫 종류가 생기면 여기에 그 이름이 들어 있는지(contains)를 더한다.
	@Test
	void 운영_코드의_알림_종류는_규칙을_지킨다() {
		assertThat(MAIN.contain(NotificationType.class)).as("운영 코드를 읽어 왔다").isTrue();
		assertThat(violations(declaredTypes(MAIN))).isEmpty();
	}

	// 운영 코드에서는 아직 찾을 것이 없으므로, 찾아내는 코드가 맞는지는 본보기를 읽혀서 본다.
	// 상수마다 몸통을 가진 enum의 상수 클래스(WithConstantBody$1)는 종류가 아니다.
	@Test
	void 선언된_종류를_찾아낸다() {
		assertThat(declaredTypes(SAMPLES)).containsExactlyInAnyOrder(SampleNotificationTypes.Valid.class,
				SampleNotificationTypes.WithConstantBody.class, SampleNotificationTypes.BadFormat.class,
				SampleNotificationTypes.WrongModule.class, SampleNotificationTypes.PrefixWithoutDot.class,
				SampleNotificationTypes.Duplicated.class, SampleNotificationTypes.DuplicatedWithin.class,
				SampleNotificationTypes.NotEnum.class);
	}

	@Test
	void 규칙을_지킨_종류에는_위반이_없다() {
		assertThat(violations(
				List.of(SampleNotificationTypes.Valid.class, SampleNotificationTypes.WithConstantBody.class)))
			.isEmpty();
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

	@Test
	void 선언한_모듈의_이름으로_시작하지_않는_이름을_잡는다() {
		assertThat(violations(List.of(SampleNotificationTypes.WrongModule.class))).singleElement()
			.satisfies(violation -> assertThat(violation).contains("billing.sample-sent")
				.contains("모듈의 이름(notification.)으로 시작하지"));
	}

	// 앞부분만 같은 다른 이름(notificationx)은 그 모듈의 이름이 아니다.
	@Test
	void 모듈의_이름은_점까지_맞아야_한다() {
		assertThat(violations(List.of(SampleNotificationTypes.PrefixWithoutDot.class))).singleElement()
			.satisfies(violation -> assertThat(violation).contains("notificationx.sample-sent")
				.contains("모듈의 이름(notification.)으로 시작하지"));
	}

	@Test
	void 다른_종류가_이미_쓴_이름을_잡는다() {
		assertThat(violations(List.of(SampleNotificationTypes.Valid.class, SampleNotificationTypes.Duplicated.class)))
			.singleElement()
			.satisfies(violation -> assertThat(violation).contains("notification.sample-sent").contains("겹친다"));
	}

	@Test
	void 한_종류_안에서_겹치는_이름을_잡는다() {
		assertThat(violations(List.of(SampleNotificationTypes.DuplicatedWithin.class))).singleElement()
			.satisfies(violation -> assertThat(violation).contains("SAMPLE_REPEATED_AGAIN").contains("겹친다"));
	}

	// 모듈 패키지 밖에 둔 종류는 어느 모듈의 이름으로 시작해야 하는지 정할 수 없다.
	@Test
	void 모듈_패키지_밖에_둔_종류를_잡는다() {
		assertThat(violations(List.of(OutsideModule.class))).singleElement()
			.satisfies(violation -> assertThat(violation).contains("OutsideModule").contains("모듈 패키지 안에"));
	}

	// core 아래의 기능은 하위 패키지 이름을 쓰고(com.jey.core.member → member), 그 밖은 모듈 이름을 쓴다.
	// 본보기는 모두 core 아래에 있어서, 그 밖의 모듈(처음 쓰는 곳이 여기다)은 이름을 정하는 함수로 직접 본다.
	@Test
	void 종류의_이름이_시작해야_하는_모듈_이름을_패키지에서_정한다() {
		assertThat(moduleOf("com.jey.dongtanpass")).contains("dongtanpass");
		assertThat(moduleOf("com.jey.dongtanpass.application")).contains("dongtanpass");
		assertThat(moduleOf("com.jey.billing.api")).contains("billing");
		assertThat(moduleOf("com.jey.core.member")).contains("member");
		assertThat(moduleOf("com.jey.core.notification.api")).contains("notification");
		assertThat(moduleOf("com.jey.core")).isEmpty();
		assertThat(moduleOf("com.jey")).isEmpty();
	}

	// 요청의 생성자는 이벤트 ID를 아무 값으로나 받는다. 매번 새 값을 넣으면 다시 맡긴 알림을 가려낼 수 없다.
	// 만드는 곳은 이벤트에서 ID와 대상을 가져오는 causedBy 하나로 한다.
	@Test
	void 알림_요청은_causedBy로만_만든다() {
		noClasses().that()
				.doNotHaveFullyQualifiedName(NotificationRequest.class.getName())
				.should()
				.callConstructorWhere(DescribedPredicate.describe("NotificationRequest constructor",
						call -> call.getTargetOwner().isEquivalentTo(NotificationRequest.class)))
				.check(MAIN);
	}

	private static List<Class<?>> declaredTypes(JavaClasses classes) {
		List<Class<?>> types = new ArrayList<>();
		for (JavaClass candidate : classes) {
			if (!candidate.isAssignableTo(NotificationType.class) || candidate.isInterface()) {
				continue;
			}
			Class<?> type = candidate.reflect();
			// 상수마다 몸통을 가진 enum은 상수의 클래스가 따로 생긴다. 종류는 그 상수를 가진 enum이다.
			if (type.getSuperclass() != null && type.getSuperclass().isEnum()) {
				continue;
			}
			types.add(type);
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
			Optional<String> module = moduleOf(type.getPackageName());
			if (module.isEmpty()) {
				violations.add(type.getName() + ": 알림 종류는 모듈 패키지 안에 둔다");
				continue;
			}
			String prefix = module.get() + ".";
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

	// 모듈 패키지 밖(com.jey, com.jey.core 바로 아래)이면 비어 있다.
	private static Optional<String> moduleOf(String packageName) {
		String[] segments = packageName.split("\\.");
		if (segments.length < 3) {
			return Optional.empty();
		}
		if (!segments[2].equals("core")) {
			return Optional.of(segments[2]);
		}
		return (segments.length < 4) ? Optional.empty() : Optional.of(segments[3]);
	}

	// 이 테스트는 com.jey 바로 아래에 있다. 모듈 패키지 밖에 둔 종류의 본보기.
	private enum OutsideModule implements NotificationType {

		SAMPLE_SENT;

		@Override
		public String code() {
			return "notification.sample-sent";
		}

	}

}
