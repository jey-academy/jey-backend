package com.jey.core.campus.web;

import com.jey.core.campus.domain.AcademyClass;
import com.jey.core.shared.Money;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param baseFee8 8회 수업 기준 수강료
 * @param bookFee 교재비. 교재가 없으면 0
 */
record ClassCreateRequest(@NotNull Long campusId, @NotBlank @Size(max = AcademyClass.MAX_NAME_LENGTH) String name,
		@NotNull Money baseFee8, @NotNull Money bookFee) {
}
