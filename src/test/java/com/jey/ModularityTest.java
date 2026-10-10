package com.jey;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

import static org.assertj.core.api.Assertions.assertThat;

class ModularityTest {

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
		var coreClasses = new ClassFileImporter()
				.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
				.importPackages("com.jey.core");

		SlicesRuleDefinition.slices().matching("com.jey.core.(*)..")
				.should().beFreeOfCycles()
				.allowEmptyShould(true)
				.check(coreClasses);
	}

	// build/spring-modulith-docs 에 모듈 다이어그램(PlantUML)과 모듈 캔버스를 만든다.
	@Test
	void 모듈_문서를_생성한다() {
		new Documenter(modules).writeDocumentation();
	}

}
