package com.jey.core.campus.web;

import com.jey.core.campus.domain.Campus;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

record CampusResponse(@Schema(requiredMode = RequiredMode.REQUIRED) Long id,
		@Schema(requiredMode = RequiredMode.REQUIRED) String name) {

	static CampusResponse from(Campus campus) {
		return new CampusResponse(campus.getId(), campus.getName());
	}

}
