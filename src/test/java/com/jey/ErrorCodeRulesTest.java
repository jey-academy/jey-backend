package com.jey;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.jey.core.shared.ErrorCode;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// 에러 코드 값은 프론트와의 계약이다. 서로 다른 enum이 같은 값을 내보내면 프론트는 두 오류를 구분할 수 없다.
// 같은 접두사를 여러 enum이 나눠 쓰는 경우(AUTH_)가 있어, 값이 겹치지 않는지 전체를 모아 확인한다.
class ErrorCodeRulesTest {

	@Test
	void 에러_코드_값은_전체에서_겹치지_않는다() {
		List<String> codes = allCodes();

		assertThat(codes).as("찾은 에러 코드").contains("COMMON_FORBIDDEN", "AUTH_INVALID_CREDENTIALS",
				"AUTH_ROLE_NOT_ALLOWED");
		assertThat(duplicates(codes)).as("겹치는 에러 코드 값").isEmpty();
	}

	@Test
	void 에러_코드_값은_모듈_접두사와_대문자_스네이크_형식이다() {
		assertThat(allCodes()).allMatch(code -> code.matches("[A-Z]+(_[A-Z0-9]+)+"));
	}

	// 규칙이 실제로 겹침을 잡는지 확인한다.
	@Test
	void 겹치는_값이_있으면_찾아낸다() {
		assertThat(duplicates(List.of("AUTH_A", "AUTH_B", "AUTH_A"))).containsExactly("AUTH_A");
	}

	private static List<String> allCodes() {
		List<String> codes = new ArrayList<>();
		new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
				.importPackages("com.jey")
				.stream()
				.filter(type -> type.isEnum() && type.isAssignableTo(ErrorCode.class))
				.forEach(type -> {
					for (Object constant : type.reflect().getEnumConstants()) {
						codes.add(((ErrorCode) constant).code());
					}
				});
		return codes;
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
