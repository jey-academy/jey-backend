package com.jey.core.campus.web;

import java.util.List;

import com.jey.core.auth.api.AllowedRoles;
import com.jey.core.auth.api.UserRole;
import com.jey.core.campus.domain.CampusRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/campuses")
class CampusController {

	private final CampusRepository campuses;

	CampusController(CampusRepository campuses) {
		this.campuses = campuses;
	}

	@AllowedRoles({ UserRole.ADMIN, UserRole.STAFF })
	@GetMapping
	List<CampusResponse> list() {
		return campuses.findAllByOrderByIdAsc().stream().map(CampusResponse::from).toList();
	}

}
