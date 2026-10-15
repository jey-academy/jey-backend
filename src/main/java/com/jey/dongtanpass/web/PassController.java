package com.jey.dongtanpass.web;

import com.jey.core.auth.api.AllowedRoles;
import com.jey.core.auth.api.UserRole;
import com.jey.dongtanpass.application.ConsentNotice;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 연계 학원이 패스를 발급하고 장부를 본다. 꺼진 학원의 계정은 PartnerActiveInterceptor가 막는다(ADR-0034).
// 관리자도 쓰지 않는다. 연계 학원 식별자가 없고, 발급은 결제를 확인한 그 학원의 일이다.
@RestController
@RequestMapping("/api/v1/dongtanpass")
@AllowedRoles(UserRole.PARTNER)
class PassController {

	@Operation(summary = "고지 문구", description = "발급 화면에 보여 줄 지금 문구와 버전. 발급 요청에 이 버전을 함께 보낸다")
	@GetMapping("/consent-notice")
	ConsentNoticeResponse consentNotice() {
		return ConsentNoticeResponse.from(ConsentNotice.CURRENT);
	}

}
