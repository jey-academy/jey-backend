package com.jey.core.campus.web;

import java.math.BigDecimal;

import com.jey.core.campus.domain.DiscountType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 할인 유형의 값을 통째로 바꾼다. 빠진 필드를 "그대로 둔다"로 읽지 않는다. 코드는 바꿀 수 없다.
 *
 * @param rate 할인율. 5%는 0.05. 0보다 크고 1 이하, 소수 넷째 자리까지
 */
@Schema(description = "할인 유형의 값을 통째로 바꾼다. 바꾸지 않는 값도 모두 보낸다. 코드는 바꿀 수 없다")
record DiscountTypeUpdateRequest(@NotBlank @Size(max = DiscountType.MAX_NAME_LENGTH) String name,
		@Schema(description = "할인율. 퍼센트가 아니라 비율이다(5%는 0.05). 0보다 크고 1 이하, 소수 넷째 자리까지",
				example = "0.05") @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1") @Digits(
						integer = 1, fraction = DiscountType.RATE_SCALE) BigDecimal rate,
		@Schema(description = "사용 여부. false로 보내면 할인을 끈다") @NotNull Boolean active) {
}
