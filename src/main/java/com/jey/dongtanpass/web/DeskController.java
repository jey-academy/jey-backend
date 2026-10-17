package com.jey.dongtanpass.web;

import com.jey.core.auth.api.AllowedRoles;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.NotIdempotent;
import com.jey.dongtanpass.application.DeskPassService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 데스크 직원이 학부모의 패스를 확인한다. 패스를 받는 지점인지는 서비스가 본다(다른 지점 직원은 403).
@RestController
@RequestMapping("/api/v1/dongtanpass/passes")
@AllowedRoles({ UserRole.STAFF, UserRole.ADMIN })
class DeskController {

	private final DeskPassService deskPassService;

	DeskController(DeskPassService deskPassService) {
		this.deskPassService = deskPassService;
	}

	@Operation(summary = "데스크 패스 확인",
			description = "학부모 화면의 QR에서 읽은 토큰으로 패스를 확인하고, 사용 처리에 쓸 패스 ID를 준다. 한 토큰은 한 번만 통과한다. "
					+ "토큰이 틀렸거나 만료됐거나 이미 쓴 것이면 이유를 가리지 않고 400(DONGTANPASS_QR_INVALID) 하나다. 학부모 화면에서 QR을 새로 받아 다시 읽는다. "
					+ "패스를 받지 않는 지점의 직원은 403(DONGTANPASS_CAMPUS_NOT_PROVIDING). "
					+ "쓸 수 없는 패스는 409(DONGTANPASS_PASS_EXPIRED, DONGTANPASS_PASS_CANCELED, DONGTANPASS_PASS_ALREADY_REDEEMED)이고 이때도 토큰은 쓴 것이다")
	@NotIdempotent("바꾸는 자료가 없다. 같은 토큰을 다시 보내면 이미 쓴 토큰이라 400이어야 한다(처음의 응답을 다시 주지 않는다)")
	@PostMapping("/verify")
	VerifiedPassResponse verifyPass(@Valid @RequestBody PassVerifyRequest body,
			@AuthenticationPrincipal AuthenticatedUser user) {
		return VerifiedPassResponse.from(deskPassService.verifyQr(body.qrToken(), user));
	}

}
