package com.jey.core.member.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.LocalDate;

import com.jey.core.member.domain.Student;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * 학생의 등록일로 받을 수 있는 날짜. 범위는 {@link Student#MIN_REGISTERED_AT}부터 {@link Student#MAX_REGISTERED_AT}까지다.
 * 값이 없는 것은 따지지 않는다(있어야 하는 자리에는 {@code @NotNull}을 같이 붙인다).
 *
 * <p>여기서 거르지 않으면 엔티티가 예외를 던져 500이 된다.
 */
@Documented
@Constraint(validatedBy = RegisteredDate.Validator.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
@interface RegisteredDate {

	String message() default "2000-01-01부터 2099-12-31 사이의 날짜여야 합니다.";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	class Validator implements ConstraintValidator<RegisteredDate, LocalDate> {

		@Override
		public boolean isValid(LocalDate value, ConstraintValidatorContext context) {
			return value == null || Student.isRegisteredAtInRange(value);
		}

	}

}
