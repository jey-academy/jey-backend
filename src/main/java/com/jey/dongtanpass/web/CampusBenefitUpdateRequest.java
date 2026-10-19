package com.jey.dongtanpass.web;

import java.math.BigDecimal;

import com.jey.dongtanpass.domain.Pass;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

@Schema(description = "JEY 발급 패스의 할인율 바꾸기")
record CampusBenefitUpdateRequest(
		@Schema(description = "할인율. 퍼센트가 아니라 비율이다(5%는 0.05). 0보다 크고 1 이하, 소수 넷째 자리까지",
				example = "0.05") @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1") @Digits(
						integer = 1, fraction = Pass.DISCOUNT_RATE_SCALE) BigDecimal discountRate) {
}
