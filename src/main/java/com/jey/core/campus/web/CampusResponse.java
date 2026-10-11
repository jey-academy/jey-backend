package com.jey.core.campus.web;

import com.jey.core.campus.domain.Campus;

record CampusResponse(Long id, String name) {

	static CampusResponse from(Campus campus) {
		return new CampusResponse(campus.getId(), campus.getName());
	}

}
