package com.jey.core.campus.web;

import com.jey.core.campus.domain.AcademyClass;
import com.jey.core.shared.Money;

/**
 * 수강료표의 한 줄. 관리자에게만 내보낸다.
 *
 * @param baseFee8 8회 수업 기준 수강료
 */
record ClassFeeResponse(Long id, Long campusId, String name, Money baseFee8, Money bookFee, boolean active) {

	static ClassFeeResponse from(AcademyClass academyClass) {
		return new ClassFeeResponse(academyClass.getId(), academyClass.getCampusId(), academyClass.getName(),
				academyClass.getBaseFee8(), academyClass.getBookFee(), academyClass.isActive());
	}

}
