package com.jey;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.jey.core.shared.PageLimits;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.validation.constraints.Max;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

// 목록의 page에 상한이 없으면 아주 큰 값(2147483647)에서 건너뛸 줄 수가 정수 범위를 넘어 조회가 서버 오류로 끝난다.
// 새 목록을 만들 때 상한을 빠뜨리면 같은 일이 다시 난다. 컨트롤러가 받는 page를 모두 모아 검사한다:
// 메서드의 page 파라미터(주소로 받는 것)와, 본문으로 받는 타입의 page 필드(학생 검색처럼 조건을 본문으로 받는 것).
//
// 본문은 그 타입이 직접 가진 page 필드만 본다. 다른 타입 안에 넣었거나(중첩), 상위 타입에서 물려받았거나, 이름이 page가 아니면
// 찾지 못한다. 페이지를 그렇게 받는 목록을 만들 때는 이 검사를 넓힌다.
class PageLimitRulesTest {

	private static final JavaClasses MAIN = new ClassFileImporter()
			.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages("com.jey");

	@Test
	void 컨트롤러가_받는_page에는_상한이_있다() {
		List<Page> pages = pages();

		// 감사 로그와 계정 목록(파라미터), 학생 검색(본문). 종류마다 하나도 못 찾았다면 찾는 방법이 틀린 것이다
		// (파라미터 이름이 컴파일에 남지 않는 등).
		assertThat(pages).filteredOn(page -> !page.inBody()).hasSizeGreaterThanOrEqualTo(2);
		assertThat(pages).filteredOn(Page::inBody).hasSizeGreaterThanOrEqualTo(1);
		assertThat(pages).allSatisfy(page -> {
			Max max = page.element().getAnnotation(Max.class);
			assertThat(max).as(page.where() + "에 @Max(PageLimits.MAX_PAGE)가 없다").isNotNull();
			assertThat(max.value()).as(page.where()).isEqualTo(PageLimits.MAX_PAGE);
		});
	}

	private static List<Page> pages() {
		List<Page> pages = new ArrayList<>();
		for (JavaClass type : MAIN) {
			if (!type.isAnnotatedWith(RestController.class)) {
				continue;
			}
			for (Method method : type.reflect().getDeclaredMethods()) {
				String where = type.getSimpleName() + "#" + method.getName();
				for (Parameter parameter : method.getParameters()) {
					if (parameter.getName().equals("page") && isInteger(parameter.getType())) {
						pages.add(new Page(parameter, where, false));
					}
					if (parameter.isAnnotationPresent(RequestBody.class)) {
						pageField(parameter.getType())
							.ifPresent(field -> pages.add(new Page(field, where + "의 본문 "
									+ parameter.getType().getSimpleName() + ".page", true)));
					}
				}
			}
		}
		return pages;
	}

	// record의 구성 요소에 붙인 검증 애너테이션은 같은 이름의 필드에도 붙는다.
	private static Optional<Field> pageField(Class<?> bodyType) {
		for (Field field : bodyType.getDeclaredFields()) {
			if (field.getName().equals("page") && isInteger(field.getType())) {
				return Optional.of(field);
			}
		}
		return Optional.empty();
	}

	private static boolean isInteger(Class<?> type) {
		return type == int.class || type == Integer.class || type == long.class || type == Long.class;
	}

	private record Page(AnnotatedElement element, String where, boolean inBody) {
	}

}
