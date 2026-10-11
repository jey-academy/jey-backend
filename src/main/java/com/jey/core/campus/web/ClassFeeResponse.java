package com.jey.core.campus.web;

import com.jey.core.campus.domain.AcademyClass;
import com.jey.core.shared.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/**
 * 수강료표의 한 줄. 관리자에게만 내보낸다.
 *
 * @param baseFee8 8회 수업 기준 수강료
 */
@Schema(description = "수강료표의 한 줄. 관리자만 받는다. 꺼진 반도 포함한다")
record ClassFeeResponse(@Schema(requiredMode = RequiredMode.REQUIRED) Long id,
		@Schema(requiredMode = RequiredMode.REQUIRED) Long campusId,
		@Schema(requiredMode = RequiredMode.REQUIRED) String name,
		@Schema(description = "8회 수업 기준 수강료(원). 1원 단위의 정수",
				requiredMode = RequiredMode.REQUIRED) Money baseFee8,
		@Schema(description = "교재비(원). 교재가 없으면 0", requiredMode = RequiredMode.REQUIRED) Money bookFee,
		@Schema(description = "운영 여부. 꺼진 반은 새로 고를 수 없다",
				requiredMode = RequiredMode.REQUIRED) boolean active) {

	static ClassFeeResponse from(AcademyClass academyClass) {
		return new ClassFeeResponse(academyClass.getId(), academyClass.getCampusId(), academyClass.getName(),
				academyClass.getBaseFee8(), academyClass.getBookFee(), academyClass.isActive());
	}

}
