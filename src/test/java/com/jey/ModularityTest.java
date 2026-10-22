package com.jey;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

class ModularityTest {

	private static final String CORE = "com.jey.core";

	// 규칙이 위반을 잡는지 보기 위한 본보기(테스트 소스). 기초 모듈과 같은 모양의 패키지다.
	private static final String SAMPLE = "com.jey.samplecore";

	private final ApplicationModules modules = ApplicationModules.of(JeyBackendApplication.class);

	@Test
	void 모듈_경계를_지킨다() {
		modules.verify();
	}

	@Test
	void core_dongtanpass_billing이_모듈로_인식된다() {
		assertThat(modules.stream().map(module -> module.getIdentifier().toString()))
				.containsExactlyInAnyOrder("core", "dongtanpass", "billing");
	}

	// 아래의 규칙들은 "com.jey.core 바로 아래의 패키지 하나가 하위 패키지 하나"라는 모양에 기대고 있다.
	// 모양이 달라지면(한 단계 깊어지거나 이름이 바뀌면) 규칙이 아무것도 보지 않으면서 통과할 수 있다. 그래서 모양부터 본다.
	// 하위 패키지를 더하거나 빼면 여기서 실패한다. 그때 이 목록을 고친다.
	@Test
	void core의_하위_패키지는_정해진_것들이다() {
		Set<String> found = new TreeSet<>();
		for (JavaClass type : coreClasses()) {
			String subPackage = subPackageOf(CORE, type.getPackageName());
			if (subPackage != null) {
				found.add(subPackage);
			}
		}

		assertThat(found).containsExactlyInAnyOrder("account", "audit", "auth", "campus", "config", "file", "member",
				"notification", "shared", "sheets", "web");
	}

	// 하위 패키지 밖(com.jey.core 바로 아래)에 놓인 타입은 어느 하위 패키지에도 속하지 않아 경계 규칙이 보지 않는다.
	// 거기에 클래스를 두면 그 클래스는 모든 하위 패키지의 안쪽을 쓸 수 있고, 다른 하위 패키지가 그것을 거쳐 쓰는 우회로가 된다.
	@Test
	void core_바로_아래에는_package_info만_둔다() {
		List<String> directlyUnderCore = coreClasses().stream()
			.filter(type -> type.getPackageName().equals(CORE))
			.map(JavaClass::getSimpleName)
			.toList();

		assertThat(directlyUnderCore).containsExactly("package-info");
	}

	// Modulith는 모듈 사이만 검사한다. core 안의 하위 패키지끼리 순환은 여기서 막는다.
	@Test
	void core_하위_패키지끼리_순환_의존하지_않는다() {
		SlicesRuleDefinition.slices().matching("com.jey.core.(*)..")
				.should().beFreeOfCycles()
				.check(coreClasses());
	}

	// Modulith는 다른 모듈이 core의 무엇을 쓰는지만 본다(각 하위 패키지의 api와 shared). core 안에서 하위 패키지끼리
	// 서로의 안쪽(서비스, 저장소, 도메인)을 쓰는 것은 보지 않는다. 그것을 여기서 막는다 (ADR-0012).
	// 안쪽을 쓰기 시작하면 순환이 아니어도 하위 패키지를 따로 고치거나 떼어 낼 수 없게 된다.
	// 쓰려는 것이 api에 없으면 그 하위 패키지의 api에 올리고 쓴다.
	//
	// 못 보는 것: 다른 하위 패키지 안쪽의 컴파일 시점 상수(static final인 글자와 숫자)를 쓰는 것. 컴파일러가 값을 그 자리에 박아
	// 넣어 클래스 파일에 참조가 남지 않는다. 글자로 적은 클래스 이름(Class.forName 등)도 같다.
	@Test
	void core_하위_패키지는_서로의_api와_shared만_쓴다() {
		subPackagesUseOnlyApiAndShared(CORE).check(coreClasses());
	}

	@Test
	void 하위_패키지_경계_규칙은_다른_하위_패키지의_안쪽을_쓰는_코드를_잡는다() {
		List<String> violations = subPackagesUseOnlyApiAndShared(SAMPLE).evaluate(sampleClasses())
			.getFailureReport()
			.getDetails();

		// 필드로 가진 것: 다른 하위 패키지의 안쪽(beta.internal)과, api가 아닌 곳에 둔 서비스(alpha).
		assertThat(violations).filteredOn(violation -> violation.contains("GammaService"))
			.hasSize(2)
			.anySatisfy(violation -> assertThat(violation).contains("BetaInternal"))
			.anySatisfy(violation -> assertThat(violation).contains("AlphaService"));
		// 필드 없이 메서드만 부른 것.
		assertThat(violations).filteredOn(violation -> violation.contains("GammaCaller"))
			.isNotEmpty()
			.allSatisfy(violation -> assertThat(violation).contains("BetaInternal"));
		// 이름만 api로 시작하는 패키지(apix)는 api가 아니다.
		assertThat(violations).filteredOn(violation -> violation.contains("GammaApixUser"))
			.singleElement()
			.satisfies(violation -> assertThat(violation).contains("BetaApix"));
		// shared는 다른 하위 패키지를 api라도 쓰지 않는다.
		assertThat(violations).filteredOn(violation -> violation.contains("SharedViolator"))
			.singleElement()
			.satisfies(violation -> assertThat(violation).contains("BetaApi"));
		// 어기는 본보기 넷 밖에서는 위반이 없다.
		assertThat(violations).allSatisfy(violation -> assertThat(violation).containsAnyOf("GammaService",
				"GammaCaller", "GammaApixUser", "SharedViolator"));
	}

	// 다른 하위 패키지의 api와 그 아래, shared, 자기 하위 패키지의 안쪽은 쓸 수 있다.
	@Test
	void 하위_패키지_경계_규칙은_api와_shared와_자기_안쪽을_쓰는_코드를_잡지_않는다() {
		var withoutViolators = sampleClasses().that(new DescribedPredicate<JavaClass>("어기는 본보기를 뺀 것") {
			@Override
			public boolean test(JavaClass type) {
				return !type.getPackageName().startsWith(SAMPLE + ".gamma")
						&& !type.getSimpleName().endsWith("Violator");
			}
		});

		assertThat(withoutViolators).as("본보기를 읽어 왔다").hasSizeGreaterThanOrEqualTo(7);
		subPackagesUseOnlyApiAndShared(SAMPLE).check(withoutViolators);
	}

	// build/spring-modulith-docs 에 모듈 다이어그램(PlantUML)과 모듈 캔버스를 만든다.
	@Test
	void 모듈_문서를_생성한다() {
		new Documenter(modules).writeDocumentation();
	}

	/**
	 * {@code base} 바로 아래의 패키지(하위 패키지)끼리는 서로의 {@code api}(와 그 아래)와 {@code shared}만 쓴다.
	 * 같은 하위 패키지 안에서는 무엇이든 쓴다. {@code shared}는 모두가 쓰는 바닥이라 다른 하위 패키지를 쓰지 않는다
	 * ({@code api}라도). {@code base}에 바로 놓인 타입은 보지 않는다(그런 타입을 두지 않는 것은 따로 본다).
	 */
	private static ArchRule subPackagesUseOnlyApiAndShared(String base) {
		return classes().that().resideInAPackage(base + "..").should(new ArchCondition<JavaClass>(
				"다른 하위 패키지의 api와 shared만 쓴다. 쓰려는 것이 api에 없으면 그 하위 패키지의 api에 올린다") {
			@Override
			public void check(JavaClass origin, ConditionEvents events) {
				String from = subPackageOf(base, origin.getPackageName());
				if (from == null) {
					return;
				}
				for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
					String targetPackage = dependency.getTargetClass().getPackageName();
					String to = subPackageOf(base, targetPackage);
					if (to == null || to.equals(from) || to.equals("shared")) {
						continue;
					}
					String api = base + "." + to + ".api";
					boolean targetIsApi = targetPackage.equals(api) || targetPackage.startsWith(api + ".");
					if (from.equals("shared") || !targetIsApi) {
						events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
					}
				}
			}
		});
	}

	// com.jey.core.member.web → member. base 밖이거나 base에 바로 놓였으면 null.
	private static String subPackageOf(String base, String packageName) {
		if (!packageName.startsWith(base + ".")) {
			return null;
		}
		String rest = packageName.substring(base.length() + 1);
		int dot = rest.indexOf('.');
		return (dot < 0) ? rest : rest.substring(0, dot);
	}

	private static JavaClasses coreClasses() {
		JavaClasses classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages(CORE);
		assertThat(classes).as("기초 모듈의 클래스를 읽어 왔다").hasSizeGreaterThan(50);
		return classes;
	}

	private static JavaClasses sampleClasses() {
		return new ClassFileImporter().importPackages(SAMPLE);
	}

}
