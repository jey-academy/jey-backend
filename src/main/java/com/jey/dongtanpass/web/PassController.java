package com.jey.dongtanpass.web;

import com.jey.core.auth.api.AllowedRoles;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.Idempotent;
import com.jey.dongtanpass.application.ConsentNotice;
import com.jey.dongtanpass.application.PassService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 연계 학원이 패스를 발급하고 장부를 본다. 꺼진 학원의 계정은 PartnerActiveInterceptor가 막는다(ADR-0034).
// 관리자도 쓰지 않는다. 연계 학원 식별자가 없고, 발급은 결제를 확인한 그 학원의 일이다.
@RestController
@RequestMapping("/api/v1/dongtanpass")
@AllowedRoles(UserRole.PARTNER)
class PassController {

	private final PassService passService;

	PassController(PassService passService) {
		this.passService = passService;
	}

	@Operation(summary = "고지 문구", description = "발급 화면에 보여 줄 지금 문구와 버전. 발급 요청에 이 버전을 함께 보낸다")
	@GetMapping("/consent-notice")
	ConsentNoticeResponse consentNotice() {
		return ConsentNoticeResponse.from(ConsentNotice.CURRENT);
	}

	@Operation(summary = "패스 발급", description = "결제를 확인한 뒤 보낸다. 링크 토큰은 이 응답에만 있다. 잃어버리면 링크를 새로 만든다")
	@Idempotent
	@PostMapping("/passes")
	@ResponseStatus(HttpStatus.CREATED)
	PassIssueResponse issue(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody PassIssueRequest body) {
		return PassIssueResponse.from(passService.issue(partnerIdOf(user), body.toNewPass()));
	}

	// 연계 학원 계정에는 학원 식별자가 있다(세션 확인이 그 조합만 통과시킨다). 없으면 설정이 어긋난 것이다.
	private static long partnerIdOf(AuthenticatedUser user) {
		if (user.partnerId() == null) {
			throw new IllegalStateException("연계 학원 식별자가 없는 연계 학원 계정이다: accountId=" + user.id());
		}
		return user.partnerId();
	}

}
