package com.jey.core.member.web;

import com.jey.core.auth.api.AllowedRoles;
import com.jey.core.auth.api.UserRole;
import com.jey.core.member.GuardianService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 학부모를 따로 만들거나 목록으로 보는 경로는 없다. 학부모는 학생을 등록하거나 학생에게 붙일 때 생긴다.
@RestController
@RequestMapping("/api/v1/guardians")
@AllowedRoles({ UserRole.ADMIN, UserRole.STAFF })
class GuardianController {

	private final GuardianService guardianService;

	GuardianController(GuardianService guardianService) {
		this.guardianService = guardianService;
	}

	@PutMapping("/{id}")
	GuardianProfileResponse update(@PathVariable long id, @Valid @RequestBody GuardianUpdateRequest body) {
		return GuardianProfileResponse.from(guardianService.update(id, body.name(), body.phone()));
	}

}
