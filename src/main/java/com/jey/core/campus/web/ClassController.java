package com.jey.core.campus.web;

import java.util.List;

import com.jey.core.auth.api.AllowedRoles;
import com.jey.core.auth.api.UserRole;
import com.jey.core.campus.ClassService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 수강료는 관리자만 본다(ADR-0021). 직원이 부르는 메서드는 금액 필드가 없는 ClassResponse만 돌려준다.
@RestController
@RequestMapping("/api/v1")
class ClassController {

	private final ClassService classService;

	ClassController(ClassService classService) {
		this.classService = classService;
	}

	// 반은 지점으로 묶지 않는다. 직원도 다른 지점의 반을 본다.
	@AllowedRoles({ UserRole.ADMIN, UserRole.STAFF })
	@GetMapping("/classes")
	List<ClassResponse> classes(@RequestParam(required = false) Long campusId) {
		return classService.activeClasses(campusId).stream().map(ClassResponse::from).toList();
	}

	@AllowedRoles(UserRole.ADMIN)
	@GetMapping("/class-fees")
	List<ClassFeeResponse> classFees(@RequestParam(required = false) Long campusId) {
		return classService.allClasses(campusId).stream().map(ClassFeeResponse::from).toList();
	}

	@AllowedRoles(UserRole.ADMIN)
	@PostMapping("/classes")
	@ResponseStatus(HttpStatus.CREATED)
	ClassFeeResponse create(@Valid @RequestBody ClassCreateRequest body) {
		return ClassFeeResponse
				.from(classService.create(body.campusId(), body.name(), body.baseFee8(), body.bookFee()));
	}

	@AllowedRoles(UserRole.ADMIN)
	@PutMapping("/classes/{id}")
	ClassFeeResponse update(@PathVariable long id, @Valid @RequestBody ClassUpdateRequest body) {
		return ClassFeeResponse
				.from(classService.update(id, body.name(), body.baseFee8(), body.bookFee(), body.active()));
	}

}
