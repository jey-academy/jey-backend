package com.jey;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.temporal.Temporal;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.SessionAttribute;

import static org.assertj.core.api.Assertions.assertThat;

// 주소(경로와 쿼리 문자열)는 브라우저의 방문 기록과 앞단의 접근 로그에 남는다. 서버 코드로 막을 수 없는 곳이다 (ADR-0032).
// 그래서 이름이나 전화번호처럼 사람을 가리키는 값은 주소로 받지 않고 본문으로 받는다(학생 검색: POST …/search).
//
// 컨트롤러가 주소로 받는 값 가운데 "글자가 실릴 수 있는 것"을 모두 모아 아래 목록과 맞춰 본다. 새로 더하면 여기서 실패한다.
// 그때 그 값이 주소에 남아도 되는지 정한다: 남아도 되면(코드가 정한 이름) 목록에 더하고, 사람을 가리키는 값이면 본문으로 옮긴다.
//
// 값의 종류가 정해져 있는 타입만 묻지 않고 넘긴다: 숫자, 참·거짓, enum, 날짜와 시각, UUID.
// 그 밖의 타입은 모두 묻는다. 글자(String)뿐 아니라 글자에서 만들어지는 값 객체(PhoneNumber 등), 목록, Map, Optional,
// 그리고 쿼리 파라미터를 묶어 받는 객체가 여기에 든다.
//
// 못 보는 것: 요청 객체(HttpServletRequest, WebRequest)를 받아 getParameter로 직접 읽는 코드.
class AddressTextRulesTest {

	private static final JavaClasses MAIN = new ClassFileImporter()
			.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages("com.jey");

	// 주소로 받아도 되는 값. "컨트롤러#메서드(파라미터)"로 적는다.
	private static final Set<String> ALLOWED = Set.of(
			// 감사 로그의 행위 이름({모듈}.{사건})과 대상의 종류(student 등). 코드가 정한 이름이고 사람을 가리키지 않는다.
			"AuditLogController#auditLogs(action)",
			"AuditLogController#auditLogs(targetType)");

	@Test
	void 컨트롤러가_주소로_받는_값_가운데_글자가_실릴_수_있는_것은_허용한_것뿐이다() {
		Scan scan = scan();

		// 하나도 못 찾았다면 찾는 방법이 틀린 것이다(컨트롤러를 못 찾았거나, 파라미터 이름이 컴파일에 남지 않았다).
		assertThat(scan.handlers).as("찾은 요청 처리 메서드 수").isGreaterThan(10);
		assertThat(scan.fromAddress).as("주소로 받는 파라미터 수(숫자 등 묻지 않는 것 포함)").isGreaterThan(10);
		assertThat(scan.questionable).as("주소로 받는 값. 사람을 가리키는 값이면 본문으로 받는다(ADR-0032)")
			.containsExactlyInAnyOrderElementsOf(ALLOWED);
	}

	private static Scan scan() {
		Scan scan = new Scan();
		for (JavaClass type : MAIN) {
			Class<?> controller = type.reflect();
			// @RestController처럼 @Controller를 품은 애너테이션도 본다.
			if (controller.isAnnotation() || !AnnotatedElementUtils.hasAnnotation(controller, Controller.class)) {
				continue;
			}
			// 상위 클래스에서 물려받은 메서드도 본다. @GetMapping 등은 @RequestMapping을 품고 있다.
			for (Method method : ReflectionUtils.getUniqueDeclaredMethods(controller)) {
				if (!AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
					continue;
				}
				scan.handlers++;
				for (Parameter parameter : method.getParameters()) {
					if (!comesFromAddress(parameter)) {
						continue;
					}
					scan.fromAddress++;
					if (!hasFixedKindOfValue(parameter.getType())) {
						scan.questionable
							.add(controller.getSimpleName() + "#" + method.getName() + "(" + parameter.getName() + ")");
					}
				}
			}
		}
		return scan;
	}

	// 경로 변수와 쿼리 파라미터다. 애너테이션이 없는 파라미터도 스프링이 쿼리 파라미터에서 채운다(글자 하나든, 묶어 받는 객체든).
	// 본문·헤더·쿠키 등 주소가 아닌 곳에서 오는 것과, 스프링이 넣어 주는 것(요청, 응답, 세션, 로그인 정보)만 뺀다.
	private static boolean comesFromAddress(Parameter parameter) {
		if (parameter.isAnnotationPresent(RequestBody.class) || parameter.isAnnotationPresent(RequestHeader.class)
				|| parameter.isAnnotationPresent(CookieValue.class) || parameter.isAnnotationPresent(RequestPart.class)
				|| parameter.isAnnotationPresent(RequestAttribute.class)
				|| parameter.isAnnotationPresent(SessionAttribute.class) || parameter.isAnnotationPresent(Value.class)
				|| parameter.isAnnotationPresent(AuthenticationPrincipal.class)) {
			return false;
		}
		String typeName = parameter.getType().getName();
		return !typeName.startsWith("jakarta.servlet.") && !typeName.startsWith("org.springframework.");
	}

	// 값의 종류가 정해져 있어 사람을 가리키는 글자가 실리지 않는 타입.
	private static boolean hasFixedKindOfValue(Class<?> type) {
		return (type.isPrimitive() && type != char.class) || Number.class.isAssignableFrom(type) || type == Boolean.class
				|| type.isEnum() || Temporal.class.isAssignableFrom(type) || type == UUID.class;
	}

	private static final class Scan {

		private final Set<String> questionable = new TreeSet<>();

		private int handlers;

		private int fromAddress;

	}

}
