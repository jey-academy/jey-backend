package com.jey;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 쿼리 문자열에는 검색어(학생 이름, 전화번호 뒷자리)가 실린다. 로그에는 경로(getRequestURI)만 남긴다 (ADR-0028).
// 쿼리 문자열을 읽는 코드를 한 곳으로 묶어 두면, 로그에 찍는 코드가 새로 생길 때 여기서 걸린다.
// 실제로 찍히지 않는지는 RequestIdApiTest가 본다.
class LogRulesTest {

	// 같은 키로 다른 요청을 보냈는지 가리는 지문에 쓴다. 해시로만 쓰고 남기지 않는다.
	private static final String ALLOWED_READER = "com.jey.core.web.IdempotencyFilter";

	private static final DescribedPredicate<JavaMethodCall> READS_QUERY_STRING = new DescribedPredicate<>(
			"요청의 쿼리 문자열을 읽는다(getQueryString)") {
		@Override
		public boolean test(JavaMethodCall call) {
			return call.getTarget().getName().equals("getQueryString")
					&& call.getTargetOwner().isAssignableTo(HttpServletRequest.class);
		}
	};

	private static final ArchRule NO_QUERY_STRING = noClasses().that()
			.doNotHaveFullyQualifiedName(ALLOWED_READER)
			.should().callMethodWhere(READS_QUERY_STRING)
			.because("쿼리 문자열에는 검색어가 실린다. 로그와 예외 문구에는 경로(getRequestURI)만 쓴다");

	@Test
	void 운영_코드는_쿼리_문자열을_읽지_않는다() {
		var mainClasses = new ClassFileImporter()
				.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
				.importPackages("com.jey");

		NO_QUERY_STRING.check(mainClasses);
	}

	// 규칙이 실제로 위반을 잡는지 확인한다.
	@Test
	void 쿼리_문자열을_읽으면_규칙에_걸린다() {
		var violating = new ClassFileImporter().importClasses(LogsQueryString.class);

		assertThatThrownBy(() -> NO_QUERY_STRING.check(violating))
				.isInstanceOf(AssertionError.class)
				.hasMessageContaining("getQueryString");
	}

	// 요청을 감싼 타입으로 불러도 같은 값을 읽는다.
	@Test
	void 감싼_요청에서_읽어도_규칙에_걸린다() {
		var violating = new ClassFileImporter().importClasses(LogsQueryStringOfWrapper.class);

		assertThatThrownBy(() -> NO_QUERY_STRING.check(violating))
				.isInstanceOf(AssertionError.class)
				.hasMessageContaining("getQueryString");
	}

	@Test
	void 경로만_읽는_것은_허용한다() {
		var allowed = new ClassFileImporter().importClasses(LogsPathOnly.class);

		NO_QUERY_STRING.check(allowed);
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

	static class LogsPathOnly {

		String describe(HttpServletRequest request) {
			return request.getMethod() + " " + request.getRequestURI();
		}

	}

}
