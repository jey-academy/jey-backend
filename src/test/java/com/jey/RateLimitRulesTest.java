package com.jey;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.jey.core.auth.api.RateLimit;
import com.jey.core.auth.api.RateLimitName;
import com.jey.core.auth.api.SampleRateLimits;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

// 계정별 횟수 한도의 이름은 횟수를 세는 키에 들어간다 (ADR-0026). 두 한도가 같은 이름을 쓰면 서로의 횟수를 같이 세어,
// 한쪽을 많이 쓴 계정이 다른 쪽에서도 막힌다. 이름을 바꾸면 그때까지 센 횟수를 잃어 막혀 있던 계정이 풀린다.
// 선언된 이름을 모두 모아 미리 검사한다.
class RateLimitRulesTest {

	private static final JavaClasses MAIN = MainClasses.get();

	// 규칙이 위반을 잡는지 보기 위한 본보기들(테스트 소스).
	private static final JavaClasses SAMPLES = new ClassFileImporter()
			.importPackages(SampleRateLimits.class.getPackageName());

	// RateLimit 밖에서는 그 생성자를 부르지도(new) 가리키지도(::new) 않는다.
	private static final ArchRule ONLY_THROUGH_FACTORY = ConstructorRules.constructedOnlyInside(RateLimit.class);

	@Test
	void 운영_코드의_한도_이름은_규칙을_지킨다() {
		assertThat(violations(declaredNames(MAIN))).isEmpty();
	}

	// 이름을 바꾸거나 지우면 여기서 실패한다. 그때까지 센 횟수를 잃어도 되는지 정하고 고친다.
	// 새 이름을 더했을 때도 여기에 더한다.
	@Test
	void 지금_쓰는_한도_이름은_그대로다() {
		assertThat(names(declaredNames(MAIN)))
			.as("한도의 이름. 이름을 바꾸면 그때까지 센 횟수를 잃어 막혀 있던 계정이 풀린다. "
					+ "바꾼 것이 아니라 새로 더한 것일 때만 이 목록에 더한다")
			.containsExactlyInAnyOrder("auth.password-change", "member.phone-reveal", "member.phone-change",
					"member.phone-probe", "dongtanpass.holder-relink", "dongtanpass.holder-relink-client",
					"dongtanpass.pass-no-verify", "dongtanpass.holder-check", "dongtanpass.redeem");
	}

	// 이름을 글자로 적어 만든 한도는 선언된 이름에 없어 위의 검사에 걸리지 않는다. 만드는 곳은 RateLimit.of 하나로 한다.
	@Test
	void 한도는_선언한_이름으로만_만든다() {
		ONLY_THROUGH_FACTORY.check(MAIN);
	}

	// 생성자를 부르는 것(new)뿐 아니라 가리키는 것(::new)도 같은 길이다. 부르는 것만 보면 가리키는 쪽이 빠져나간다.
	@Test
	void 생성자를_부르거나_가리키는_코드를_잡고_팩터리를_쓰는_코드는_잡지_않는다() {
		assertThat(ONLY_THROUGH_FACTORY.evaluate(classesOf(SampleRateLimits.CallsConstructor.class)).hasViolation())
			.as("new RateLimit(…)")
			.isTrue();
		assertThat(
				ONLY_THROUGH_FACTORY.evaluate(classesOf(SampleRateLimits.ReferencesConstructor.class)).hasViolation())
			.as("RateLimit::new")
			.isTrue();
		assertThat(ONLY_THROUGH_FACTORY.evaluate(classesOf(SampleRateLimits.UsesFactory.class)).hasViolation())
			.as("RateLimit.of(…)")
			.isFalse();
	}

	@Test
	void enum이_아닌_이름으로는_한도를_만들_수_없다() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> RateLimit.of(new SampleRateLimits.NotEnum(), 5, Duration.ofMinutes(1)))
			.withMessageContaining("enum으로 선언");
		RateLimitName lambda = () -> "auth.from-lambda";
		assertThatIllegalArgumentException().isThrownBy(() -> RateLimit.of(lambda, 5, Duration.ofMinutes(1)));
	}

	@Test
	void 선언한_이름으로_만든_한도는_그_이름을_키에_쓴다() {
		RateLimit limit = RateLimit.of(SampleRateLimits.Valid.SAMPLE_THING, 5, Duration.ofMinutes(1));

		assertThat(limit.name()).isEqualTo("auth.sample-thing");
		assertThat(limit.max()).isEqualTo(5);
	}

	// 상수마다 몸통을 가진 enum의 상수 클래스(WithConstantBody$1)는 이름을 선언한 것이 아니다. 그 enum 하나만 든다.
	@Test
	void 선언된_이름을_찾아낸다() {
		assertThat(declaredNames(SAMPLES)).containsExactlyInAnyOrder(SampleRateLimits.Valid.class,
				SampleRateLimits.BadFormat.class, SampleRateLimits.WrongModule.class,
				SampleRateLimits.PrefixWithoutDot.class, SampleRateLimits.Duplicated.class,
				SampleRateLimits.DuplicatedWithin.class, SampleRateLimits.NotEnum.class,
				SampleRateLimits.WithConstantBody.class);
	}

	@Test
	void 상수마다_몸통을_가진_enum의_이름도_본다() {
		assertThat(names(List.of(SampleRateLimits.WithConstantBody.class))).containsExactly("auth.constant-body");
		assertThat(violations(List.of(SampleRateLimits.WithConstantBody.class))).isEmpty();
	}

	@Test
	void 규칙을_지킨_이름에는_위반이_없다() {
		assertThat(violations(List.of(SampleRateLimits.Valid.class))).isEmpty();
	}

	@Test
	void enum이_아닌_이름을_잡는다() {
		assertThat(violations(List.of(SampleRateLimits.NotEnum.class))).singleElement()
			.satisfies(violation -> assertThat(violation).contains("NotEnum").contains("enum으로 선언"));
	}

	@Test
	void 형식에_맞지_않는_이름을_잡는다() {
		assertThat(violations(List.of(SampleRateLimits.BadFormat.class))).singleElement()
			.satisfies(violation -> assertThat(violation).contains("auth.Sample_Thing").contains("형식"));
	}

	@Test
	void 선언한_모듈의_이름으로_시작하지_않는_이름을_잡는다() {
		assertThat(violations(List.of(SampleRateLimits.WrongModule.class))).singleElement()
			.satisfies(violation -> assertThat(violation).contains("billing.sample-thing")
				.contains("모듈의 이름(auth.)으로 시작하지"));
	}

	// 앞부분만 같은 다른 이름(authx)은 그 모듈의 이름이 아니다.
	@Test
	void 모듈의_이름은_점까지_맞아야_한다() {
		assertThat(violations(List.of(SampleRateLimits.PrefixWithoutDot.class))).singleElement()
			.satisfies(violation -> assertThat(violation).contains("authx.sample-thing")
				.contains("모듈의 이름(auth.)으로 시작하지"));
	}

	@Test
	void 다른_enum이_이미_쓴_이름을_잡는다() {
		assertThat(violations(List.of(SampleRateLimits.Valid.class, SampleRateLimits.Duplicated.class)))
			.singleElement()
			.satisfies(violation -> assertThat(violation).contains("auth.sample-thing").contains("겹친다"));
	}

	@Test
	void 한_enum_안에서_겹치는_이름을_잡는다() {
		assertThat(violations(List.of(SampleRateLimits.DuplicatedWithin.class))).singleElement()
			.satisfies(violation -> assertThat(violation).contains("SECOND").contains("겹친다"));
	}

	// 모듈 패키지 밖에 둔 이름은 어느 모듈의 이름으로 시작해야 하는지 정할 수 없다.
	@Test
	void 모듈_패키지_밖에_둔_이름을_잡는다() {
		assertThat(violations(List.of(OutsideModule.class))).singleElement()
			.satisfies(violation -> assertThat(violation).contains("OutsideModule").contains("모듈 패키지 안에"));
	}

	@Test
	void 패키지에서_모듈의_이름을_구한다() {
		assertThat(moduleOf("com.jey.dongtanpass")).contains("dongtanpass");
		assertThat(moduleOf("com.jey.billing.application")).contains("billing");
		assertThat(moduleOf("com.jey.core.member")).contains("member");
		assertThat(moduleOf("com.jey.core.auth.api")).contains("auth");
		assertThat(moduleOf("com.jey.core")).isEmpty();
		assertThat(moduleOf("com.jey")).isEmpty();
	}

	private static List<Class<?>> declaredNames(JavaClasses classes) {
		List<Class<?>> types = new ArrayList<>();
		for (JavaClass candidate : classes) {
			if (!candidate.isAssignableTo(RateLimitName.class) || candidate.isInterface()) {
				continue;
			}
			Class<?> type = candidate.reflect();
			// 상수마다 몸통을 가진 enum은 상수의 클래스가 따로 생긴다. 이름을 선언한 것은 그 상수를 가진 enum이다.
			// 람다와 익명 클래스는 이름을 선언한 것이 아니다(그렇게 만든 이름은 RateLimit.of가 받지 않는다).
			if ((type.getSuperclass() != null && type.getSuperclass().isEnum()) || type.isAnonymousClass()
					|| type.isSynthetic()) {
				continue;
			}
			types.add(type);
		}
		return types;
	}

	// enum이 아닌 것은 건너뛴다(그것은 violations가 위반으로 적는다).
	private static List<String> names(List<Class<?>> types) {
		List<String> names = new ArrayList<>();
		for (Class<?> type : types) {
			if (!type.isEnum()) {
				continue;
			}
			for (Object constant : type.getEnumConstants()) {
				names.add(((RateLimitName) constant).code());
			}
		}
		return names;
	}

	private static JavaClasses classesOf(Class<?> type) {
		// 메서드 참조와 람다는 그 코드를 가진 클래스 안에 남는다. 안쪽에 선언한 타입도 같이 읽는다.
		List<Class<?>> types = new ArrayList<>(List.of(type));
		types.addAll(List.of(type.getDeclaredClasses()));
		return new ClassFileImporter().importClasses(types);
	}

	// 규칙을 어긴 곳마다 한 줄. 비어 있으면 모두 지킨 것이다.
	private static List<String> violations(List<Class<?>> types) {
		List<String> violations = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (Class<?> type : types) {
			if (!type.isEnum()) {
				violations.add(type.getName() + ": 한도의 이름은 enum으로 선언한다");
				continue;
			}
			Optional<String> module = moduleOf(type.getPackageName());
			if (module.isEmpty()) {
				violations.add(type.getName() + ": 한도의 이름은 모듈 패키지 안에 둔다");
				continue;
			}
			String prefix = module.get() + ".";
			for (Object constant : type.getEnumConstants()) {
				String name = ((RateLimitName) constant).code();
				String where = type.getName() + "." + constant + "(" + name + ")";
				if (!RateLimit.isValidName(name)) {
					violations.add(where + ": 이름이 {모듈}.{대상} 형식의 소문자가 아니다");
				}
				else if (!name.startsWith(prefix)) {
					violations.add(where + ": 이름이 선언한 모듈의 이름(" + prefix + ")으로 시작하지 않는다");
				}
				if (!seen.add(name)) {
					violations.add(where + ": 다른 한도와 이름이 겹친다");
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

	// 이 테스트는 com.jey 바로 아래에 있다. 모듈 패키지 밖에 둔 이름의 본보기.
	private enum OutsideModule implements RateLimitName {

		SAMPLE;

		@Override
		public String code() {
			return "auth.outside-module";
		}

	}

}
