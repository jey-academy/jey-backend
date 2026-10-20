package com.jey.dongtanpass.web;

import com.jey.core.auth.api.AllowedRoles;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.NotIdempotent;
import com.jey.dongtanpass.application.PartnerPassService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 연계 학원이 JEY 발급 패스를 확인하고 사용 처리한다(반대 방향, ADR-0040). 꺼진 학원은 인터셉터가 403으로 막는다.
 */
@RestController
@RequestMapping("/api/v1/dongtanpass/partner-redemptions")
class PartnerRedemptionController {

	private final PartnerPassService partnerPassService;

	PartnerRedemptionController(PartnerPassService partnerPassService) {
		this.partnerPassService = partnerPassService;
	}

	@Operation(summary = "연계 학원 패스 확인",
			description = "학부모 화면의 QR에서 읽은 토큰(qrToken)이나 패스 번호(passNo) 중 하나로 JEY 발급 패스를 확인하고, 다음 단계에 쓸 패스 ID를 준다. "
					+ "둘 다 보내거나 둘 다 없으면 400(COMMON_BAD_REQUEST). QR: 한 토큰은 한 번만 통과한다. 틀렸거나 만료됐거나 이미 쓴 토큰은 400(DONGTANPASS_QR_INVALID). "
					+ "패스 번호: 없는 번호는 404(DONGTANPASS_PASS_NOT_FOUND), 계정마다 정해진 횟수를 넘으면 429(DONGTANPASS_PASS_NO_VERIFY_LIMITED, Retry-After). "
					+ "연계 학원이 발급한 패스는 409(DONGTANPASS_PASS_FOR_CAMPUS_USE), 이 학원에서 이미 JEY 패스 할인을 받은 학생은 409(DONGTANPASS_STUDENT_ALREADY_USED_AT_PARTNER) — "
					+ "이 둘은 토큰을 쓰지 않는다. 쓸 수 없는 패스는 409(DONGTANPASS_PASS_EXPIRED, DONGTANPASS_PASS_CANCELED, DONGTANPASS_PASS_ALREADY_REDEEMED)")
	@AllowedRoles(UserRole.PARTNER)
	@NotIdempotent("바꾸는 자료가 없다. 같은 토큰을 다시 보내면 이미 쓴 토큰이라 400이어야 한다(처음의 응답을 다시 주지 않는다)")
	@PostMapping("/verify")
	PartnerVerifiedPassResponse verify(@Valid @RequestBody PassVerifyRequest body,
			@AuthenticationPrincipal AuthenticatedUser user) {
		if (body.qrToken() != null) {
			return PartnerVerifiedPassResponse.from(partnerPassService.verifyQr(body.qrToken(), user));
		}
		return PartnerVerifiedPassResponse.from(partnerPassService.verifyPassNo(body.passNo(), user));
	}

}
