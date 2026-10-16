package com.jey.dongtanpass.web;

import com.jey.core.auth.api.AllowedRoles;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.Idempotent;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 끈 연계 학원을 막는 장치(PartnerActiveInterceptor)만 검증하기 위한 테스트 전용 컨트롤러.
// 연계 학원이 부르는 실제 API(발급, 장부)가 아직 없어서 둔다. 패스 모듈의 패키지에 있어야 장치의 대상이 된다.
// 테스트 소스라 운영 빌드에는 포함되지 않는다. API 명세에도 싣지 않는다(ApiSpecTest).
@Hidden
@RestController
@RequestMapping("/api/v1/dongtanpass/test-partner-area")
class PartnerAreaTestController {

	@AllowedRoles(UserRole.PARTNER)
	@GetMapping
	String partnerArea() {
		return "ok";
	}

	// 멱등 키를 받는 POST. 꺼진 학원의 403이 멱등 응답으로 보관되지 않는지 본다.
	@AllowedRoles(UserRole.PARTNER)
	@Idempotent
	@PostMapping
	String partnerAction() {
		return "done";
	}

	@AllowedRoles(UserRole.ADMIN)
	@GetMapping("/admin")
	String adminArea() {
		return "ok";
	}

	@AllowedRoles({ UserRole.ADMIN, UserRole.STAFF })
	@GetMapping("/desk")
	String deskArea() {
		return "ok";
	}

}
