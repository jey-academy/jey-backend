package com.jey.core.campus.web;

import java.math.BigDecimal;

import com.jey.core.campus.domain.DiscountType;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/**
 * @param rate 할인율. 5%는 0.05
 * @param active 꺼진 할인은 새로 고를 수 없다
 */
@Schema(description = "할인 유형. 꺼진 것도 내려가므로 고르는 목록에서는 active로 거른다")
record DiscountTypeResponse(@Schema(requiredMode = RequiredMode.REQUIRED) Long id,
		@Schema(description = "할인 유형 코드. 만든 뒤 바뀌지 않는다", example = "SIBLING_5",
				requiredMode = RequiredMode.REQUIRED) String code,
		@Schema(requiredMode = RequiredMode.REQUIRED) String name,
		@Schema(description = "할인율. 퍼센트가 아니라 비율이다(5%는 0.05)", example = "0.05",
				requiredMode = RequiredMode.REQUIRED) BigDecimal rate,
		@Schema(description = "사용 여부. 꺼진 할인은 새로 고를 수 없다",
				requiredMode = RequiredMode.REQUIRED) boolean active) {

	static DiscountTypeResponse from(DiscountType discountType) {
		return new DiscountTypeResponse(discountType.getId(), discountType.getCode(), discountType.getName(),
				discountType.getRate(), discountType.isActive());
	}

}
