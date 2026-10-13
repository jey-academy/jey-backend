package com.jey;

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

	// Modulith는 모듈 사이만 검사한다. core 안의 하위 패키지끼리 순환은 여기서 막는다.
	@Test
	void core_하위_패키지끼리_순환_의존하지_않는다() {
		SlicesRuleDefinition.slices().matching("com.jey.core.(*)..")
				.should().beFreeOfCycles()
				.allowEmptyShould(true)
				.check(coreClasses());
	}

	// Modulith는 다른 모듈이 core의 무엇을 쓰는지만 본다(각 하위 패키지의 api와 shared). core 안에서 하위 패키지끼리
	// 서로의 안쪽(서비스, 저장소, 도메인)을 쓰는 것은 보지 않는다. 그것을 여기서 막는다 (ADR-0012).
	// 안쪽을 쓰기 시작하면 순환이 아니어도 하위 패키지를 따로 고치거나 떼어 낼 수 없게 된다.
	// 쓰려는 것이 api에 없으면 그 하위 패키지의 api에 올리고 쓴다.
	@Test
	void core_하위_패키지는_서로의_api와_shared만_쓴다() {
		subPackagesUseOnlyApiAndShared(CORE).check(coreClasses());
	}

	@Test
	void 하위_패키지_경계_규칙은_다른_하위_패키지의_안쪽을_쓰는_코드를_잡는다() {
		var result = subPackagesUseOnlyApiAndShared(SAMPLE).evaluate(sampleClasses());

		assertThat(result.hasViolation()).isTrue();
		// 어기는 것은 GammaService가 쓰는 둘뿐이다: 다른 하위 패키지의 안쪽(beta.internal)과, api가 아닌 곳에 둔 서비스(alpha).
		assertThat(result.getFailureReport().getDetails()).hasSize(2)
			.allSatisfy(detail -> assertThat(detail).contains("GammaService"))
			.anySatisfy(detail -> assertThat(detail).contains("BetaInternal"))
			.anySatisfy(detail -> assertThat(detail).contains("AlphaService"));
	}

	// 다른 하위 패키지의 api와 그 아래, shared, 자기 하위 패키지의 안쪽은 쓸 수 있다.
	@Test
	void 하위_패키지_경계_규칙은_api와_shared와_자기_안쪽을_쓰는_코드를_잡지_않는다() {
		var withoutViolator = sampleClasses().that(new DescribedPredicate<JavaClass>(
				"어기는 본보기를 뺀 것") {
			@Override
			public boolean test(JavaClass type) {
				return !type.getPackageName().startsWith(SAMPLE + ".gamma");
			}
		});

		assertThat(withoutViolator).as("본보기를 읽어 왔다").hasSizeGreaterThanOrEqualTo(6);
		subPackagesUseOnlyApiAndShared(SAMPLE).check(withoutViolator);
	}

	// build/spring-modulith-docs 에 모듈 다이어그램(PlantUML)과 모듈 캔버스를 만든다.
	@Test
	void 모듈_문서를_생성한다() {
		new Documenter(modules).writeDocumentation();
	}

	/**
	 * {@code base} 바로 아래의 패키지(하위 패키지)끼리는 서로의 {@code api}(와 그 아래)와 {@code shared}만 쓴다.
	 * 같은 하위 패키지 안에서는 무엇이든 쓴다. {@code base}에 바로 놓인 타입(package-info)은 보지 않는다.
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
					if (!targetPackage.equals(api) && !targetPackage.startsWith(api + ".")) {
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
		return new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages(CORE);
	}

	private static JavaClasses sampleClasses() {
		return new ClassFileImporter().importPackages(SAMPLE);
	}

}
