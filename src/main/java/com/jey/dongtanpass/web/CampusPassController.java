package com.jey.dongtanpass.web;

import java.time.LocalDate;

import com.jey.core.auth.api.AllowedRoles;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.Idempotent;
import com.jey.core.shared.PageLimits;
import com.jey.dongtanpass.application.CampusPassService;
import com.jey.dongtanpass.application.PassBenefits;
import com.jey.dongtanpass.application.PassLedger;
import com.jey.dongtanpass.application.ConsentNotice;
import com.jey.dongtanpass.domain.PassDirection;
import com.jey.dongtanpass.domain.PassState;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

	private final PassLedger passLedger;

	private final PassBenefits passBenefits;

	CampusPassController(CampusPassService campusPassService, PassLedger passLedger, PassBenefits passBenefits) {
		this.campusPassService = campusPassService;
		this.passLedger = passLedger;
		this.passBenefits = passBenefits;
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

	@Operation(summary = "링크 새로 만들기",
			description = "JEY 발급 패스의 학부모 링크 토큰을 새로 만든다. 예전 링크는 바로 쓸 수 없게 된다. 새 토큰은 이 응답에만 있다. 사용됐거나 취소된 패스는 409")
	@Idempotent
	@PostMapping("/{id}/link")
	PassLinkResponse renewLink(@PathVariable long id) {
		return PassLinkResponse.from(campusPassService.renewLink(id));
	}

	@Operation(summary = "JEY 패스 취소", description = "쓰지 않은 JEY 발급 패스를 취소한다. 기간이 지난 패스도 된다. 결제 환불은 따로 한다. 사용됐거나 이미 취소된 패스는 409")
	@Idempotent
	@PostMapping("/{id}/cancel")
	PassCancelResponse cancel(@PathVariable long id) {
		return PassCancelResponse.from(campusPassService.cancel(id));
	}

	@Operation(summary = "JEY 발급 장부", description = "JEY가 발급한 패스. 최신 발급 순이다. 연계 학원이 발급한 패스는 없다. 학부모 번호는 없다")
	@GetMapping
	CampusPassPageResponse ledger(@Parameter(description = "이 학생의 패스만. 안 주면 모두") @RequestParam(
			required = false) Long studentId,
			@Parameter(description = "이 발급일(한국 날짜)부터, 포함. 2000-01-01~2099-12-31") @RequestParam(
					required = false) LocalDate from,
			@Parameter(description = "이 발급일(한국 날짜)까지, 포함. from보다 앞이거나 2000-01-01~2099-12-31 밖이면 400") @RequestParam(
					required = false) LocalDate to,
			@Parameter(description = "오늘 기준으로 보이는 상태. 안 주면 모두") @RequestParam(required = false) PassState status,
			@Parameter(description = PageLimits.PAGE_DESCRIPTION) @RequestParam(defaultValue = "0") @Min(0) @Max(
					PageLimits.MAX_PAGE) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(PassController.MAX_PAGE_SIZE) int size) {
		return CampusPassPageResponse.from(passLedger.campusPage(passBenefits.current().campusId(), studentId, from,
				to, status, page, size));
	}

}
