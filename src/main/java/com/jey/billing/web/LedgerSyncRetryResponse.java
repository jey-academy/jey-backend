package com.jey.billing.web;

import com.jey.billing.application.LedgerSyncView;
import com.jey.billing.domain.LedgerSyncReason;
import com.jey.billing.domain.LedgerSyncStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "다시 반영을 한 번 시도한 뒤의 상태")
record LedgerSyncRetryResponse(@Schema(requiredMode = RequiredMode.REQUIRED) long id,
		@Schema(description = "SYNCED(장부에 썼다), UNSYNCED(또 못 썼다 — reason을 본다), PENDING(구글 장애가 아직이라 정한 간격대로 다시 한다)",
				requiredMode = RequiredMode.REQUIRED) LedgerSyncStatus status,
		@Schema(description = "UNSYNCED일 때의 이유. 아니면 null. " + LedgerSyncDocs.REASON, requiredMode = RequiredMode.REQUIRED,
				nullable = true) LedgerSyncReason reason) {

	static LedgerSyncRetryResponse from(LedgerSyncView view) {
		return new LedgerSyncRetryResponse(view.id(), view.status(), view.reason());
	}

}
