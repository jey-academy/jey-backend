package com.jey.billing.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jey.billing.application.LedgerSyncService;
import com.jey.core.auth.api.AllowedRoles;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.Idempotent;
import com.jey.core.shared.PageLimits;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 장부(구글 시트)에 반영하지 못한 결제. 관리자 전용이다(장부 연결과 권한을 고치는 사람). 결제 자체는 장부와 무관하게 성공해 있다.
 * 명세의 operationId는 직접 정한다({@link InvoiceController} 참고).
 */
@RestController
@RequestMapping("/api/v1/billing/ledger-sync")
@AllowedRoles(UserRole.ADMIN)
class LedgerSyncController {

	static final int MAX_PAGE_SIZE = 100;

	private final LedgerSyncService ledger;

	LedgerSyncController(LedgerSyncService ledger) {
		this.ledger = ledger;
	}

	@Operation(operationId = "listUnsyncedLedger", summary = "장부 미반영 목록",
			description = "장부에 쓰지 못한 결제, 최근에 생긴 반영 기록부터. 구글 장애로 다시 시도하는 중인 결제는 여기 없다(정한 간격대로 다시 하고, 끝내 실패하면 GOOGLE_UNAVAILABLE로 들어온다)")
	@GetMapping("/unsynced")
	LedgerSyncPageResponse listUnsynced(
			@Parameter(description = "지점. 안 주면 전 지점") @RequestParam(required = false) Long campusId,
			@Parameter(description = PageLimits.PAGE_DESCRIPTION) @RequestParam(defaultValue = "0") @Min(0) @Max(
					PageLimits.MAX_PAGE) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
		return LedgerSyncPageResponse.from(ledger.listUnsynced(campusId, page, size));
	}

	@Operation(operationId = "retryLedgerSync", summary = "장부 다시 반영",
			description = "장부(탭, 반 블록, 빈 줄, 권한)를 고친 뒤 부른다. 요청 본문은 없다. 바로 한 번 써 보고 그 뒤의 상태를 준다. "
					+ "미반영이 아니면 409(BILLING_LEDGER_SYNC_NOT_RETRYABLE), 없으면 404(BILLING_LEDGER_SYNC_NOT_FOUND)")
	@Idempotent
	@PostMapping("/{id}/retry")
	LedgerSyncRetryResponse retry(@PathVariable long id) {
		return LedgerSyncRetryResponse.from(ledger.retryUnsynced(id));
	}

}
