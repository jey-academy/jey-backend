package com.jey.dongtanpass.web;

import com.jey.dongtanpass.application.PartnerLedgerSyncView;
import com.jey.dongtanpass.domain.PartnerLedgerReason;
import com.jey.dongtanpass.domain.PartnerLedgerStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "연계 학원 장부 다시 반영을 한 번 시도한 뒤의 상태")
record PartnerLedgerSyncRetryResponse(@Schema(requiredMode = RequiredMode.REQUIRED) long id,
		@Schema(description = "SYNCED(장부에 썼다), UNSYNCED(또 못 썼다 — reason을 본다), PENDING(구글 장애라 잠시 뒤 저절로 다시 한다), IN_PROGRESS(그 순간 다른 쪽이 반영하는 중이다 — 목록을 다시 본다)",
				requiredMode = RequiredMode.REQUIRED) PartnerLedgerStatus status,
		@Schema(description = "UNSYNCED일 때의 이유. 아니면 null. " + PartnerLedgerSyncDocs.REASON,
				requiredMode = RequiredMode.REQUIRED, nullable = true) PartnerLedgerReason reason) {

	static PartnerLedgerSyncRetryResponse from(PartnerLedgerSyncView view) {
		return new PartnerLedgerSyncRetryResponse(view.id(), view.status(), view.reason());
	}

}
