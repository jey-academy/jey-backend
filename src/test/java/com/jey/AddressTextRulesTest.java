package com.jey;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

// 주소(경로와 쿼리 문자열)는 브라우저의 방문 기록과 앞단의 접근 로그에 남는다. 서버 코드로 막을 수 없는 곳이다 (ADR-0032).
// 그래서 이름이나 전화번호처럼 사람을 가리키는 값은 주소로 받지 않고 본문으로 받는다(학생 검색: POST …/search).
//
// 컨트롤러가 주소로 받는 "글자" 값을 모두 모아 아래 목록과 맞춰 본다. 새로 더하면 여기서 실패한다.
// 그때 그 값이 주소에 남아도 되는지 정한다: 남아도 되면(정해진 이름, 코드) 목록에 더하고, 사람을 가리키는 값이면 본문으로 옮긴다.
// 숫자, 참·거짓, enum, 날짜와 시각은 보지 않는다(값의 종류가 정해져 있어 사람을 가리키는 글자가 실리지 않는다).
class AddressTextRulesTest {

	private static final JavaClasses MAIN = new ClassFileImporter()
			.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages("com.jey");

	// 주소로 받아도 되는 글자 값. "컨트롤러#메서드(파라미터)"로 적는다.
	private static final Set<String> ALLOWED = Set.of(
			// 감사 로그의 행위 이름({모듈}.{사건})과 대상의 종류(student 등). 코드가 정한 이름이고 사람을 가리키지 않는다.
			"AuditLogController#auditLogs(action)",
			"AuditLogController#auditLogs(targetType)");

	@Test
	void 컨트롤러가_주소로_받는_글자_값은_허용한_것뿐이다() {
		assertThat(textInAddress()).as("주소로 받는 글자 값. 사람을 가리키는 값이면 본문으로 받는다(ADR-0032)")
			.containsExactlyInAnyOrderElementsOf(ALLOWED);
	}

	private static Set<String> textInAddress() {
		Set<String> found = new TreeSet<>();
		for (JavaClass type : MAIN) {
			if (!type.isAnnotatedWith(RestController.class)) {
				continue;
			}
			for (Method method : type.reflect().getDeclaredMethods()) {
				for (Parameter parameter : method.getParameters()) {
					if (isText(parameter.getType()) && comesFromAddress(parameter)) {
						found.add(type.getSimpleName() + "#" + method.getName() + "(" + parameter.getName() + ")");
					}
				}
			}
		}
		return found;
	}

	// 경로 변수와 쿼리 파라미터다. 애너테이션이 없는 글자 파라미터도 스프링이 쿼리 파라미터로 읽는다.
	// 본문, 헤더, 쿠키로 받는 것만 뺀다.
	private static boolean comesFromAddress(Parameter parameter) {
		return !parameter.isAnnotationPresent(RequestBody.class) && !parameter.isAnnotationPresent(RequestHeader.class)
				&& !parameter.isAnnotationPresent(CookieValue.class);
	}

	// 글자 하나, 글자의 목록과 배열.
	private static boolean isText(Class<?> type) {
		return CharSequence.class.isAssignableFrom(type) || Collection.class.isAssignableFrom(type)
				|| (type.isArray() && CharSequence.class.isAssignableFrom(type.getComponentType()));
	}

}
