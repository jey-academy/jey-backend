package com.jey.core.campus.web;

import com.jey.core.campus.api.ClassInfo;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/**
 * 직원도 보는 반 정보. 수강료는 관리자만 보므로 이 타입에 금액 필드를 넣지 않는다({@link ClassFeeResponse}).
 */
@Schema(description = "반. 운영 중인 반만 내려간다. 수강료는 없다")
record ClassResponse(@Schema(requiredMode = RequiredMode.REQUIRED) Long id,
		@Schema(requiredMode = RequiredMode.REQUIRED) Long campusId,
		@Schema(requiredMode = RequiredMode.REQUIRED) String name) {

	// 수강료가 든 엔티티가 아니라 금액이 없는 ClassInfo에서만 만든다.
	static ClassResponse from(ClassInfo classInfo) {
		return new ClassResponse(classInfo.classId(), classInfo.campusId(), classInfo.name());
	}

}
