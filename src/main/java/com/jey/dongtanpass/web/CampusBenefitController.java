package com.jey.dongtanpass.web;

import com.jey.core.auth.api.AllowedRoles;
import com.jey.core.auth.api.UserRole;
import com.jey.dongtanpass.application.CampusBenefitService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dongtanpass/campus-benefit")
@AllowedRoles(UserRole.ADMIN)
class CampusBenefitController {

	private final CampusBenefitService campusBenefitService;

	CampusBenefitController(CampusBenefitService campusBenefitService) {
		this.campusBenefitService = campusBenefitService;
	}

	@Operation(summary = "JEY 발급 패스의 할인율", description = "연계 학원에서 쓰는 JEY 발급 패스의 지금 할인율. 발급할 때 이 값이 패스에 남는다")
	@GetMapping
	CampusBenefitResponse get() {
		return new CampusBenefitResponse(campusBenefitService.currentRate());
	}

	@Operation(summary = "JEY 발급 패스의 할인율 바꾸기", description = "이후에 발급하는 패스부터 새 값이다. 이미 발급한 패스는 발급 때 값 그대로다")
	@PutMapping
	CampusBenefitResponse change(@Valid @RequestBody CampusBenefitUpdateRequest body) {
		return new CampusBenefitResponse(campusBenefitService.change(body.discountRate()));
	}

}
