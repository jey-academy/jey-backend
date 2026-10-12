package com.jey;

import java.util.function.Function;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 쿼리 문자열에는 검색어(학생 이름, 전화번호 뒷자리)가 실린다. 로그에는 경로(getRequestURI)만 남긴다 (ADR-0028).
//
// 이 규칙이 막는 것은 하나다: 요청의 쿼리 문자열을 통째로 읽는 코드(getQueryString)가 정해진 한 곳 밖에 생기는 것.
// "주소를 로그에 찍는다"는 코드가 경로 대신 주소 전체를 찍게 되는 흔한 실수를 여기서 잡는다.
//
// 막지 못하는 것: 컨트롤러가 @RequestParam으로 받은 값을 직접 찍는 것, 예외 문구에 값이 실리는 것,
// getParameterMap 같은 다른 통로로 읽는 것. 그런 것은 이 규칙이 아니라 코드를 읽어서 걸러야 한다.
// 실제 요청에서 로그에 찍히지 않는지는 RequestIdApiTest가 몇 갈래를 본다.
class LogRulesTest {

	// 같은 키로 다른 요청을 보냈는지 가리는 지문에 쓴다. 해시로만 쓰고 남기지 않는다.
	private static final String ALLOWED_READER = "com.jey.core.web.IdempotencyFilter";

	// 호출(request.getQueryString())과 메서드 참조(HttpServletRequest::getQueryString)를 모두 본다.
	private static final DescribedPredicate<JavaAccess<?>> READS_QUERY_STRING = new DescribedPredicate<>(
			"요청의 쿼리 문자열을 읽는다(getQueryString)") {
		@Override
		public boolean test(JavaAccess<?> access) {
			return access.getTarget().getName().equals("getQueryString")
					&& access.getTargetOwner().isAssignableTo(HttpServletRequest.class);
		}
	};

	private static final ArchRule QUERY_STRING_READ_IN_ONE_PLACE = noClasses().that()
			.doNotHaveFullyQualifiedName(ALLOWED_READER)
			.should().accessTargetWhere(READS_QUERY_STRING)
			.because("쿼리 문자열에는 검색어가 실린다. 로그와 예외 문구에는 경로(getRequestURI)만 쓴다");

	@Test
	void 운영_코드에서_쿼리_문자열을_통째로_읽는_곳은_한_곳뿐이다() {
		var mainClasses = new ClassFileImporter()
				.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
				.importPackages("com.jey");

		QUERY_STRING_READ_IN_ONE_PLACE.check(mainClasses);
	}

	// 아래 셋은 규칙이 실제로 위반을 잡는지 확인한다.
	@Test
	void 쿼리_문자열을_읽으면_규칙에_걸린다() {
		assertViolation(LogsQueryString.class);
	}

	// 요청을 감싼 타입으로 불러도 같은 값을 읽는다.
	@Test
	void 감싼_요청에서_읽어도_규칙에_걸린다() {
		assertViolation(LogsQueryStringOfWrapper.class);
	}

	// 메서드 참조는 호출과 다른 모양으로 컴파일된다. 호출만 보는 규칙은 이것을 놓친다.
	@Test
	void 메서드_참조로_읽어도_규칙에_걸린다() {
		assertViolation(ReferencesQueryString.class);
	}

	@Test
	void 경로만_읽는_것은_허용한다() {
		var allowed = new ClassFileImporter().importClasses(LogsPathOnly.class);

		QUERY_STRING_READ_IN_ONE_PLACE.check(allowed);
	}

	private static void assertViolation(Class<?> violatingClass) {
		var violating = new ClassFileImporter().importClasses(violatingClass);

		assertThatThrownBy(() -> QUERY_STRING_READ_IN_ONE_PLACE.check(violating))
				.isInstanceOf(AssertionError.class)
				.hasMessageContaining("getQueryString");
	}

	static class LogsQueryString {

		String describe(HttpServletRequest request) {
			return request.getRequestURI() + "?" + request.getQueryString();
		}

	}

	static class LogsQueryStringOfWrapper {

		String describe(HttpServletRequestWrapper request) {
			return request.getQueryString();
		}

	}

	static class ReferencesQueryString {

		Function<HttpServletRequest, String> reader() {
			return HttpServletRequest::getQueryString;
		}

	}

	static class LogsPathOnly {

		String describe(HttpServletRequest request) {
			return request.getMethod() + " " + request.getRequestURI();
		}

	}

}
