package com.jey.core.campus.web;

import com.jey.core.campus.domain.AcademyClass;
import com.jey.core.shared.Money;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 반의 값을 통째로 바꾼다. 빠진 필드를 "그대로 둔다"로 읽지 않는다. 지점은 바꿀 수 없다.
 */
record ClassUpdateRequest(@NotBlank @Size(max = AcademyClass.MAX_NAME_LENGTH) String name, @NotNull Money baseFee8,
		@NotNull Money bookFee, @NotNull Boolean active) {
}
