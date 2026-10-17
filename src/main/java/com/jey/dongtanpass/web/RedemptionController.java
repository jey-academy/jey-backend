package com.jey.dongtanpass.web;

import java.time.LocalDate;

import com.jey.core.auth.api.AllowedRoles;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.PageLimits;
import com.jey.dongtanpass.application.RedemptionLedger;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 데스크가 지점의 패스 사용 내역을 본다. 소속 지점으로 묶은 자료다(직원은 자기 지점, 관리자는 전 지점, ADR-0021).
@RestController
@RequestMapping("/api/v1/dongtanpass/redemptions")
@AllowedRoles({ UserRole.STAFF, UserRole.ADMIN })
class RedemptionController {

	static final int MAX_PAGE_SIZE = 100;

	private final RedemptionLedger redemptionLedger;

	RedemptionController(RedemptionLedger redemptionLedger) {
		this.redemptionLedger = redemptionLedger;
	}

	@Operation(summary = "패스 사용 내역",
			description = "최신 사용 순이다. 직원은 지점을 고르지 않으면 소속 지점이고, 다른 지점을 고르면 403(AUTH_CAMPUS_NOT_ALLOWED). "
					+ "관리자는 고르지 않으면 전 지점이다. 기간은 사용일(한국 날짜)로 자르고 양끝을 포함하며 둘 다 필수다. "
					+ "시작일이 종료일보다 뒤이거나 2000-01-01~2099-12-31 밖이면 400(DONGTANPASS_INVALID_PERIOD), "
					+ "1년(시작일 + 1년)을 넘으면 400(DONGTANPASS_PERIOD_TOO_LONG)")
	@GetMapping
	RedemptionPageResponse list(@AuthenticationPrincipal AuthenticatedUser user,
			@Parameter(description = "지점. 안 주면 직원은 소속 지점, 관리자는 전 지점") @RequestParam(required = false) Long campusId,
			@Parameter(description = "이 사용일(한국 날짜)부터, 포함") @RequestParam LocalDate from,
			@Parameter(description = "이 사용일(한국 날짜)까지, 포함. 시작일 + 1년까지") @RequestParam LocalDate to,
			@Parameter(description = PageLimits.PAGE_DESCRIPTION) @RequestParam(defaultValue = "0") @Min(0) @Max(
					PageLimits.MAX_PAGE) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
		return RedemptionPageResponse
			.from(redemptionLedger.page(user.campusScopeFor(campusId), from, to, page, size));
	}

}
