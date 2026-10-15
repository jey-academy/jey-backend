package com.jey.dongtanpass.web;

import java.util.List;

import com.jey.core.auth.api.AllowedRoles;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.Idempotent;
import com.jey.dongtanpass.application.PartnerService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 연계 학원은 관리자가 만들고 관리한다 (ADR-0034).
@RestController
@RequestMapping("/api/v1/dongtanpass/partners")
@AllowedRoles(UserRole.ADMIN)
class PartnerController {

	private final PartnerService partnerService;

	PartnerController(PartnerService partnerService) {
		this.partnerService = partnerService;
	}

	@Operation(summary = "연계 학원 목록", description = "만든 순서다. 꺼진 학원도 나온다")
	@GetMapping
	List<PartnerResponse> partners() {
		return partnerService.list().stream().map(PartnerResponse::from).toList();
	}

	@Operation(summary = "연계 학원 만들기")
	@Idempotent
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	PartnerResponse createPartner(@Valid @RequestBody PartnerCreateRequest body) {
		return PartnerResponse.from(partnerService.create(body.name()));
	}

}
