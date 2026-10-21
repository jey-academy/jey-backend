package com.jey.billing.web;

import com.jey.billing.application.LedgerSyncView;
import com.jey.billing.domain.LedgerSyncReason;
import com.jey.billing.domain.LedgerSyncStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "다시 반영을 한 번 시도한 뒤의 상태")
record LedgerSyncRetryResponse(@Schema(requiredMode = RequiredMode.REQUIRED) long id,
		@Schema(description = "SYNCED(장부에 썼다), UNSYNCED(또 못 썼다 — reason을 본다), PENDING(구글 장애나 다른 결제의 쓰기 때문에 잠시 뒤 저절로 다시 한다), IN_PROGRESS(그 순간 다른 쪽이 반영하는 중이다 — 목록을 다시 본다), CANCELED(취소한 결제라 취소 처리를 마쳤다)",
				requiredMode = RequiredMode.REQUIRED) LedgerSyncStatus status,
		@Schema(description = "UNSYNCED일 때의 이유. 아니면 null. " + LedgerSyncDocs.REASON, requiredMode = RequiredMode.REQUIRED,
				nullable = true) LedgerSyncReason reason) {

	static LedgerSyncRetryResponse from(LedgerSyncView view) {
		return new LedgerSyncRetryResponse(view.id(), view.status(), view.reason());
	}

}
