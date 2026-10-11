package com.jey.core.campus.web;

import com.jey.core.campus.domain.AcademyClass;
import com.jey.core.shared.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param baseFee8 8회 수업 기준 수강료
 * @param bookFee 교재비. 교재가 없으면 0
 */
record ClassCreateRequest(@NotNull Long campusId,
		@Schema(description = "같은 지점 안에서 겹칠 수 없다. 앞뒤 공백은 떼고 저장한다") @NotBlank @Size(
				max = AcademyClass.MAX_NAME_LENGTH) String name,
		@Schema(description = "8회 수업 기준 수강료(원). 0보다 큰 1원 단위의 정수") @NotNull Money baseFee8,
		@Schema(description = "교재비(원). 1원 단위의 정수. 교재가 없으면 0") @NotNull Money bookFee) {
}
