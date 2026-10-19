package com.jey.dongtanpass.web;

import com.jey.core.auth.api.AllowedRoles;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.Idempotent;
import com.jey.dongtanpass.application.CampusPassService;
import com.jey.dongtanpass.application.ConsentNotice;
import com.jey.dongtanpass.domain.PassDirection;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * JEY 지점이 다니는 학생에게 발급하는 패스(연계 학원에서 쓴다). 관리자만 쓴다.
 */
@RestController
@RequestMapping("/api/v1/dongtanpass/campus-passes")
@AllowedRoles(UserRole.ADMIN)
class CampusPassController {

	private final CampusPassService campusPassService;

	CampusPassController(CampusPassService campusPassService) {
		this.campusPassService = campusPassService;
	}

	@Operation(summary = "JEY 발급 고지 문구", description = "JEY 발급 화면에 보여 줄 지금 문구와 버전. 발급 요청에 이 버전을 함께 보낸다(연계 학원 발급의 문구와 다르다)")
	@GetMapping("/consent-notice")
	ConsentNoticeResponse consentNotice() {
		return ConsentNoticeResponse.from(ConsentNotice.of(PassDirection.CAMPUS_ISSUED));
	}

	@Operation(summary = "JEY 패스 발급",
			description = "다니는 학생의 그 달 수강료 결제를 확인한 뒤 보낸다. 같은 학생이 여러 장 받을 수 있다. 링크 토큰은 이 응답에만 있다. 잃어버리면 링크를 새로 만든다")
	@Idempotent
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	CampusPassIssueResponse issue(@Valid @RequestBody CampusPassIssueRequest body) {
		return CampusPassIssueResponse.from(campusPassService.issue(body.toNewCampusPass()));
	}

}
