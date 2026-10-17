package com.jey.dongtanpass.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.jey.dongtanpass.domain.StudentNameKey;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * 학생 이름으로 맞춰 볼 수 있는 글자. 비었거나 공백(NBSP·전각 포함)뿐이면 안 된다. 공백의 기준은 이름 비교({@link StudentNameKey})와 같다.
 * 값이 없는 것은 따지지 않는다(있어야 하는 자리에는 {@code @NotNull}을 같이 붙인다).
 *
 * <p>{@code @NotBlank}는 NBSP를 공백으로 보지 않는다. 요청 record의 생성자에서 거르면 칸 이름 없는 본문 오류가 되므로 칸의 검증으로 둔다.
 */
@Documented
@Constraint(validatedBy = StudentNameText.Validator.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
@interface StudentNameText {

	String message() default "이름이 비어 있습니다.";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	class Validator implements ConstraintValidator<StudentNameText, String> {

		@Override
		public boolean isValid(String value, ConstraintValidatorContext context) {
			if (value == null) {
				return true;
			}
			try {
				StudentNameKey.of(value);
				return true;
			}
			catch (IllegalArgumentException ex) {
				return false;
			}
		}

	}

}
