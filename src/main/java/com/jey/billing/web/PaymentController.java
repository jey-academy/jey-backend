package com.jey.billing.web;

import java.time.LocalDate;

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

import com.jey.billing.application.PaymentService;
import com.jey.core.auth.api.AllowedRoles;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.Idempotent;
import com.jey.core.shared.PageLimits;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 데스크가 받은 결제(포스기 카드, 현금)의 기록. 앱은 결제를 하지 않고 받은 결과를 적는다. 결제는 청구의 지점(청구를 만들 때 학생의 지점)에 속한다.
 * 명세의 operationId는 직접 정한다({@link InvoiceController} 참고).
 */
@RestController
@RequestMapping("/api/v1/billing")
@AllowedRoles({ UserRole.STAFF, UserRole.ADMIN })
class PaymentController {

	static final int MAX_PAGE_SIZE = 100;

	private final PaymentService payments;

	PaymentController(PaymentService payments) {
		this.payments = payments;
	}

	@Operation(operationId = "recordPayment", summary = "결제 기록",
			description = "결제 전 청구에 데스크가 받은 결제를 적는다. 청구는 PAID가 된다. 위에서부터 확인한다: 교재비가 규칙 밖이면 400(BILLING_INVALID_BOOK_FEE), "
					+ "없는 청구 404(BILLING_INVOICE_NOT_FOUND), 다른 지점의 청구 403(AUTH_CAMPUS_NOT_ALLOWED), "
					+ "결제 전 청구가 아니면 409(BILLING_INVOICE_NOT_PAYABLE — 이미 결제했거나 취소한 청구), 수강료가 청구 합계와 다르면 409(BILLING_PAYMENT_AMOUNT_MISMATCH). "
					+ "드물게 동시 기록이 DB에서 막히면 409(BILLING_PAYMENT_ALREADY_EXISTS). 칸의 값을 읽지 못하면 400(COMMON_VALIDATION_FAILED), 모르는 칸은 400")
	@Idempotent
	@PostMapping("/invoices/{id}/payments")
	@ResponseStatus(HttpStatus.CREATED)
	PaymentResponse record(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
			@Valid @RequestBody PaymentRequest body) {
		return PaymentResponse.from(payments.record(id, body.method(), body.amount(), body.bookFeeOrZero(), user));
	}

	@Operation(operationId = "cancelPayment", summary = "결제 취소",
			description = "기록한 결제를 취소한다(환불은 데스크에서 따로 한다). 청구는 다시 결제 전(ISSUED)이 된다. 이미 취소한 결제는 409(BILLING_PAYMENT_ALREADY_CANCELED), "
					+ "없는 결제 404(BILLING_PAYMENT_NOT_FOUND), 다른 지점 403(AUTH_CAMPUS_NOT_ALLOWED)")
	@Idempotent
	@PostMapping("/payments/{id}/cancel")
	PaymentResponse cancel(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
		return PaymentResponse.from(payments.cancel(id, user));
	}

	@Operation(operationId = "listPayments", summary = "결제 목록",
			description = "결제일(한국 날짜)로 자르고 양끝을 포함하며 둘 다 필수다. 최신 결제 순, 취소한 결제도 있다. 직원은 지점을 고르지 않으면 소속 지점이고, 다른 지점을 고르면 403(AUTH_CAMPUS_NOT_ALLOWED). "
					+ "관리자는 고르지 않으면 전 지점이다. 시작일이 종료일보다 뒤이거나 2000-01-01~2099-12-31 밖이면 400(BILLING_INVALID_PERIOD), 1년을 넘으면 400(BILLING_PERIOD_TOO_LONG)")
	@GetMapping("/payments")
	PaymentPageResponse list(@AuthenticationPrincipal AuthenticatedUser user,
			@Parameter(description = "지점. 안 주면 직원은 소속 지점, 관리자는 전 지점") @RequestParam(required = false) Long campusId,
			@Parameter(description = "이 결제일(한국 날짜)부터, 포함") @RequestParam LocalDate from,
			@Parameter(description = "이 결제일(한국 날짜)까지, 포함. 시작일 + 1년까지") @RequestParam LocalDate to,
			@Parameter(description = PageLimits.PAGE_DESCRIPTION) @RequestParam(defaultValue = "0") @Min(0) @Max(
					PageLimits.MAX_PAGE) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
		return PaymentPageResponse.from(payments.list(user.campusScopeFor(campusId), from, to, page, size));
	}

}
