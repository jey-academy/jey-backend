package com.jey.billing.web;

import java.time.YearMonth;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.jey.billing.application.InvoiceService;
import com.jey.core.auth.api.AllowedRoles;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.Idempotent;
import com.jey.core.shared.NotIdempotent;
import com.jey.core.shared.PageLimits;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 데스크의 수강료 청구. 청구는 학생의 지점에 속한다: 직원은 소속 지점의 학생만, 관리자는 모든 지점.
 *
 * <p>명세의 operationId는 직접 정한다. 메서드 이름({@code list}, {@code get})이 다른 컨트롤러와 겹치면 springdoc이 번호를 붙이는데,
 * 새 경로가 끼면 기존 경로의 번호가 밀려 프론트의 생성 타입이 바뀐다.
 */
@RestController
@RequestMapping("/api/v1/billing")
@AllowedRoles({ UserRole.STAFF, UserRole.ADMIN })
class InvoiceController {

	static final int MAX_PAGE_SIZE = 100;

	private static final String REJECTIONS = "학생이 없으면 404(BILLING_STUDENT_NOT_FOUND), 재원 중이 아니면 409(BILLING_STUDENT_NOT_ENROLLED). "
			+ "할인 유형 코드는 대소문자를 가리지 않는다 — 같은 할인을 두 번 고르면 400(BILLING_DISCOUNT_DUPLICATED). "
			+ "반이 없으면 404(BILLING_CLASS_NOT_FOUND), 꺼진 반 409(BILLING_CLASS_INACTIVE), 학생과 다른 지점의 반 409(BILLING_CLASS_NOT_AT_STUDENT_CAMPUS). "
			+ "할인 유형이 없으면 404(BILLING_DISCOUNT_TYPE_NOT_FOUND), 꺼진 할인 409(BILLING_DISCOUNT_TYPE_INACTIVE). "
			+ "할인을 하나만 받는 설정에 둘 이상이면 400(BILLING_TOO_MANY_DISCOUNTS), 할인율의 합이 100% 이상이면 400(BILLING_DISCOUNT_TOO_LARGE). "
			+ "다른 지점의 학생이면 403(AUTH_CAMPUS_NOT_ALLOWED). 칸의 값이 틀리면 400(COMMON_VALIDATION_FAILED), 같은 반·같은 할인을 두 번 넣거나 모르는 칸이 있으면 400";

	private final InvoiceService invoices;

	InvoiceController(InvoiceService invoices) {
		this.invoices = invoices;
	}

	@Operation(operationId = "quoteInvoice", summary = "청구액 미리 계산",
			description = "저장하지 않고 계산만 한다. 청구액 = 8회 기준 수강료 × (1 − 할인율) × 횟수 ÷ 8, 1원 미만 버림. " + REJECTIONS)
	@NotIdempotent("저장하지 않는 계산이다")
	@PostMapping("/quote")
	InvoiceResponse quote(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody InvoiceRequest body) {
		return InvoiceResponse.from(invoices.quote(body.toDraft(), user));
	}

	@Operation(operationId = "issueInvoice", summary = "청구 만들기", description = "학생의 그 달 청구를 만든다. 한 학생 한 달에 열린 청구는 하나다(있으면 409 BILLING_INVOICE_ALREADY_EXISTS — 오류 본문의 invoiceId가 그 청구다. 드물게 동시 요청이 겹치면 없다). "
			+ "금액은 미리 계산과 같다. 근거가 필요한 할인(동탄고 패스)은 고를 수 없다 — 400(BILLING_DISCOUNT_NEEDS_REFERENCE), 패스 사용 기록에서 청구를 만든다(미리 계산은 된다). " + REJECTIONS)
	@Idempotent
	@PostMapping("/invoices")
	@ResponseStatus(HttpStatus.CREATED)
	InvoiceResponse issue(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody InvoiceRequest body) {
		return InvoiceResponse.from(invoices.issue(body.toDraft(), user));
	}

	@Operation(operationId = "getInvoice", summary = "청구 하나", description = "없으면 404(BILLING_INVOICE_NOT_FOUND), 다른 지점의 청구면 403(AUTH_CAMPUS_NOT_ALLOWED)")
	@GetMapping("/invoices/{id}")
	InvoiceResponse get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
		return InvoiceResponse.from(invoices.get(id, user));
	}

	@Operation(operationId = "cancelInvoice", summary = "청구 취소",
			description = "결제 전(ISSUED) 청구를 취소한다. 닫힌 청구가 되어 같은 달에 새 청구를 만들 수 있다. 결제한 청구나 이미 취소한 청구는 409(BILLING_INVOICE_NOT_CANCELABLE — 결제한 청구는 결제를 먼저 취소한다). "
					+ "없으면 404(BILLING_INVOICE_NOT_FOUND), 다른 지점 403(AUTH_CAMPUS_NOT_ALLOWED)")
	@Idempotent
	@PostMapping("/invoices/{id}/cancel")
	InvoiceResponse cancel(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
		return InvoiceResponse.from(invoices.cancel(id, user));
	}

	@Operation(operationId = "listInvoices", summary = "그 달의 청구", description = "학생 ID 순이다. 직원은 지점을 고르지 않으면 소속 지점이고, 다른 지점을 고르면 403(AUTH_CAMPUS_NOT_ALLOWED). 관리자는 고르지 않으면 전 지점이다")
	@GetMapping("/invoices")
	InvoicePageResponse list(@AuthenticationPrincipal AuthenticatedUser user,
			@Parameter(description = "지점. 안 주면 직원은 소속 지점, 관리자는 전 지점") @RequestParam(required = false) Long campusId,
			@Parameter(description = "대상 월(YYYY-MM)", example = "2026-11") @RequestParam YearMonth targetMonth,
			@Parameter(description = PageLimits.PAGE_DESCRIPTION) @RequestParam(defaultValue = "0") @Min(0) @Max(
					PageLimits.MAX_PAGE) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
		return InvoicePageResponse.from(invoices.list(user.campusScopeFor(campusId), targetMonth, page, size));
	}

}
