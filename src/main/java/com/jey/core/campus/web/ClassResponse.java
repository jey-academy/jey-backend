package com.jey.core.campus.web;

import com.jey.core.campus.domain.AcademyClass;

/**
 * 직원도 보는 반 정보. 수강료는 관리자만 보므로 이 타입에 금액 필드를 넣지 않는다({@link ClassFeeResponse}).
 */
record ClassResponse(Long id, Long campusId, String name) {

	static ClassResponse from(AcademyClass academyClass) {
		return new ClassResponse(academyClass.getId(), academyClass.getCampusId(), academyClass.getName());
	}

}
