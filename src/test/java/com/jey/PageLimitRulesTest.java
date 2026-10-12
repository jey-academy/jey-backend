package com.jey;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

import com.jey.core.shared.PageLimits;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.validation.constraints.Max;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

// 목록의 page에 상한이 없으면 아주 큰 값(2147483647)에서 건너뛸 줄 수가 정수 범위를 넘어 조회가 서버 오류로 끝난다.
// 새 목록을 만들 때 상한을 빠뜨리면 같은 일이 다시 난다. 컨트롤러의 page 파라미터를 모두 모아 검사한다.
class PageLimitRulesTest {

	private static final JavaClasses MAIN = new ClassFileImporter()
			.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages("com.jey");

	@Test
	void 컨트롤러의_page_파라미터에는_상한이_있다() {
		List<Parameter> pages = pageParameters();

		// 학생, 감사 로그, 계정 목록. 하나도 못 찾았다면 찾는 방법이 틀린 것이다(파라미터 이름이 컴파일에 남지 않는 등).
		assertThat(pages).hasSizeGreaterThanOrEqualTo(3);
		assertThat(pages).allSatisfy(page -> {
			Max max = page.getAnnotation(Max.class);
			assertThat(max).as(describe(page) + "에 @Max(PageLimits.MAX_PAGE)가 없다").isNotNull();
			assertThat(max.value()).as(describe(page)).isEqualTo(PageLimits.MAX_PAGE);
		});
	}

	private static List<Parameter> pageParameters() {
		List<Parameter> pages = new ArrayList<>();
		for (JavaClass type : MAIN) {
			if (!type.isAnnotatedWith(RestController.class)) {
				continue;
			}
			for (Method method : type.reflect().getDeclaredMethods()) {
				for (Parameter parameter : method.getParameters()) {
					if (parameter.getName().equals("page") && parameter.getType() == int.class) {
						pages.add(parameter);
					}
				}
			}
		}
		return pages;
	}

	private static String describe(Parameter page) {
		return page.getDeclaringExecutable().getDeclaringClass().getSimpleName() + "#"
				+ page.getDeclaringExecutable().getName();
	}

}
