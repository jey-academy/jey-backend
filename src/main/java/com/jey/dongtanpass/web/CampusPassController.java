package com.jey.dongtanpass.web;

import com.jey.core.auth.api.AllowedRoles;
import com.jey.core.auth.api.UserRole;
import com.jey.dongtanpass.application.ConsentNotice;
import com.jey.dongtanpass.domain.PassDirection;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * JEY 지점이 다니는 학생에게 발급하는 패스(연계 학원에서 쓴다). 관리자만 쓴다.
 */
@RestController
@RequestMapping("/api/v1/dongtanpass/campus-passes")
@AllowedRoles(UserRole.ADMIN)
class CampusPassController {

	@Operation(summary = "JEY 발급 고지 문구", description = "JEY 발급 화면에 보여 줄 지금 문구와 버전. 발급 요청에 이 버전을 함께 보낸다(연계 학원 발급의 문구와 다르다)")
	@GetMapping("/consent-notice")
	ConsentNoticeResponse consentNotice() {
		return ConsentNoticeResponse.from(ConsentNotice.of(PassDirection.CAMPUS_ISSUED));
	}

}
