package com.jey.core.campus.web;

import java.math.BigDecimal;

import com.jey.core.campus.domain.DiscountType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @param code 대문자로 시작하는 대문자·숫자·밑줄 2~30자. 만든 뒤에는 바꿀 수 없다
 * @param rate 할인율. 5%는 0.05. 0보다 크고 1 이하, 소수 넷째 자리까지
 */
record DiscountTypeCreateRequest(
		@Schema(description = "대문자로 시작하는 대문자·숫자·밑줄 2~30자. 만든 뒤에는 바꿀 수 없다",
				example = "SIBLING_5") @NotBlank @Pattern(regexp = DiscountType.CODE_PATTERN,
						message = "대문자로 시작하는 대문자·숫자·밑줄 2~30자여야 합니다") String code,
		@NotBlank @Size(max = DiscountType.MAX_NAME_LENGTH) String name,
		@Schema(description = "할인율. 퍼센트가 아니라 비율이다(5%는 0.05). 0보다 크고 1 이하, 소수 넷째 자리까지",
				example = "0.05") @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1") @Digits(
						integer = 1, fraction = DiscountType.RATE_SCALE) BigDecimal rate) {
}
