package com.jey.dongtanpass.web;

import java.util.List;

import com.jey.core.auth.api.AllowedRoles;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.Idempotent;
import com.jey.core.shared.NotIdempotent;
import com.jey.dongtanpass.application.PartnerService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

	@Operation(summary = "연계 학원의 계정 만들기",
			description = "그 학원의 공용 계정을 만든다. 만든 뒤의 이름 고치기, 차단과 풀기, 비밀번호 다시 정하기는 계정 관리(/api/v1/accounts)에서 한다. 소속은 바꿀 수 없다")
	@NotIdempotent("비밀번호가 실린 본문의 지문을 저장하지 않는다. 다시 보내면 아이디 중복(409)으로 거부된다")
	@PostMapping("/{id}/accounts")
	@ResponseStatus(HttpStatus.CREATED)
	PartnerAccountResponse createPartnerAccount(@PathVariable long id,
			@Valid @RequestBody PartnerAccountCreateRequest body) {
		return PartnerAccountResponse
			.from(partnerService.createAccount(id, body.loginId(), body.name(), body.password()));
	}

}
