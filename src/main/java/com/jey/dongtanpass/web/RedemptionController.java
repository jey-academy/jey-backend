package com.jey.dongtanpass.web;

import java.time.LocalDate;

import com.jey.core.auth.api.AllowedRoles;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.PageLimits;
import com.jey.core.shared.Idempotent;
import com.jey.dongtanpass.application.RedemptionInvoiceService;
import com.jey.dongtanpass.application.RedemptionLedger;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 데스크가 지점의 패스 사용 내역을 본다. 소속 지점으로 묶은 자료다(직원은 자기 지점, 관리자는 전 지점, ADR-0021).
@RestController
@RequestMapping("/api/v1/dongtanpass/redemptions")
@AllowedRoles({ UserRole.STAFF, UserRole.ADMIN })
class RedemptionController {

	static final int MAX_PAGE_SIZE = 100;

	private final RedemptionLedger redemptionLedger;

	private final RedemptionInvoiceService redemptionInvoices;

	RedemptionController(RedemptionLedger redemptionLedger, RedemptionInvoiceService redemptionInvoices) {
		this.redemptionLedger = redemptionLedger;
		this.redemptionInvoices = redemptionInvoices;
	}

	// operationId는 직접 정한다(메서드 이름이 다른 컨트롤러와 겹치면 기존 경로의 번호가 밀린다).
	@Operation(operationId = "issueRedemptionInvoice", summary = "사용 기록에서 청구 만들기",
			description = "패스를 쓴 학생의 그 달 청구를 만든다. 패스 할인은 서버가 붙인다(지금 할인율, 참조값 = 패스 번호, 직원이 더 고른 할인보다 먼저 계산). "
					+ "패스 할인은 이 경로로만 청구에 붙는다(수납의 청구 만들기는 받지 않는다). 사용 기록 하나에 열린 청구 하나다: 있으면 409(DONGTANPASS_REDEMPTION_ALREADY_INVOICED), "
					+ "그 청구를 취소하면 다시 만들 수 있다. 없는 사용 기록 404(DONGTANPASS_REDEMPTION_NOT_FOUND), 사용 기록의 지점이나 학생의 지금 지점을 다룰 수 없으면 403(AUTH_CAMPUS_NOT_ALLOWED), "
					+ "패스 할인이 꺼져 있으면 409(DONGTANPASS_DISCOUNT_INACTIVE). 추가 할인에 패스 할인을 또 고르면 400(BILLING_DISCOUNT_NEEDS_REFERENCE). "
					+ "반·학생·할인·한 달 한 청구의 거부는 수납의 청구 만들기와 같은 코드다(BILLING_…)")
	@Idempotent
	@PostMapping("/{id}/invoice")
	@ResponseStatus(HttpStatus.CREATED)
	RedemptionInvoiceResponse issueInvoice(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
			@Valid @RequestBody RedemptionInvoiceRequest body) {
		return RedemptionInvoiceResponse.from(redemptionInvoices.issue(id, body.targetMonth(), body.commandLines(),
				body.discountCodesOrEmpty(), user));
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
