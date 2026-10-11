package com.jey.core.campus.web;

import com.jey.core.campus.api.ClassInfo;

/**
 * 직원도 보는 반 정보. 수강료는 관리자만 보므로 이 타입에 금액 필드를 넣지 않는다({@link ClassFeeResponse}).
 */
record ClassResponse(Long id, Long campusId, String name) {

	// 수강료가 든 엔티티가 아니라 금액이 없는 ClassInfo에서만 만든다.
	static ClassResponse from(ClassInfo classInfo) {
		return new ClassResponse(classInfo.classId(), classInfo.campusId(), classInfo.name());
	}

}
