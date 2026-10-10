package com.jey.core.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.LocalDate;

import com.jey.core.shared.BusinessException;
import com.jey.core.shared.CommonErrorCode;
import com.jey.core.shared.ErrorCode;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 예외 변환만 검증하기 위한 테스트 전용 컨트롤러. 각 예외를 확정적으로 일으킨다.
// 테스트 소스라 운영 빌드에는 포함되지 않는다.
@RestController
@RequestMapping("/api/v1/test-errors")
class ErrorTestController {

	@GetMapping("/business")
	void business() {
		throw new BusinessException(CommonErrorCode.CONFLICT, "이미 사용한 패스입니다.");
	}

	@GetMapping("/module-code")
	void moduleCode() {
		throw new BusinessException(TestErrorCode.TEST_PASS_EXPIRED);
	}

	// 응답에 새면 안 되는 내부 정보 흉내. GlobalExceptionHandlerTest가 본문에 없는지 확인한다.
	@GetMapping("/unexpected")
	void unexpected() {
		throw new IllegalStateException("jdbc:mysql://secret-host:3306/jey");
	}

	@GetMapping("/denied")
	void denied() {
		throw new AccessDeniedException("내부 사유");
	}

	@PostMapping("/validated")
	void validated(@Valid @RequestBody Body body) {
	}

	// 파라미터에 직접 제약이 있으면 Spring이 본문 검증까지 메서드 검증으로 처리한다.
	@PostMapping("/validated-mixed")
	void validatedMixed(@RequestParam @Min(1) int size, @Valid @RequestBody Body body) {
	}

	@GetMapping("/param")
	void param(@RequestParam @Min(1) int size) {
	}

	@GetMapping("/query")
	void query(@Valid @ModelAttribute Query query) {
	}

	@PostMapping("/range")
	void range(@Valid @RequestBody Range range) {
	}

	@GetMapping("/return-value")
	@NotBlank
	String returnValue() {
		return "";
	}

	record Body(@NotBlank String name, @Min(1) int count) {
	}

	record Query(LocalDate from) {
	}

	@ValidRange
	record Range(int min, int max) {
	}

	// 모듈이 자기 에러 코드를 가지는 경우를 흉내 낸다. 410은 공통 코드에 매핑이 없는 상태다.
	enum TestErrorCode implements ErrorCode {

		TEST_PASS_EXPIRED;

		@Override
		public String code() {
			return name();
		}

		@Override
		public HttpStatus status() {
			return HttpStatus.GONE;
		}

		@Override
		public String message() {
			return "만료된 패스입니다.";
		}

	}

	@Target(ElementType.TYPE)
	@Retention(RetentionPolicy.RUNTIME)
	@Constraint(validatedBy = RangeValidator.class)
	@interface ValidRange {

		String message() default "최솟값이 최댓값보다 클 수 없습니다.";

		Class<?>[] groups() default {};

		Class<? extends Payload>[] payload() default {};

	}

	static class RangeValidator implements ConstraintValidator<ValidRange, Range> {

		@Override
		public boolean isValid(Range value, ConstraintValidatorContext context) {
			return value.min() <= value.max();
		}

	}

}
