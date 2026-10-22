package com.jey.dongtanpass.web;

import java.time.Instant;

import com.jey.dongtanpass.application.PartnerLedgerSyncView;
import com.jey.dongtanpass.domain.PartnerLedgerKind;
import com.jey.dongtanpass.domain.PartnerLedgerReason;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "연계 학원 장부에 반영하지 못한 패스 사용 하나. 전화번호는 싣지 않는다")
record PartnerLedgerSyncResponse(
		@Schema(description = "장부 반영 ID. 다시 반영할 때 쓴다", requiredMode = RequiredMode.REQUIRED) long id,
		@Schema(requiredMode = RequiredMode.REQUIRED) long passId,
		@Schema(description = "장부의 주인인 연계 학원(할인해 준 학원, 또는 패스를 발급한 학원)", requiredMode = RequiredMode.REQUIRED) long partnerId,
		@Schema(description = "연계 학원 이름. 학원을 찾지 못하면 null(있어서는 안 되는 자료)", requiredMode = RequiredMode.REQUIRED,
				nullable = true) String partnerName,
		@Schema(description = PartnerLedgerSyncDocs.KIND, requiredMode = RequiredMode.REQUIRED) PartnerLedgerKind kind,
		@Schema(description = "패스의 학생 이름(발급 때의 이름). 패스를 찾지 못하면 null", requiredMode = RequiredMode.REQUIRED,
				nullable = true) String studentName,
		@Schema(description = PartnerLedgerSyncDocs.REASON, requiredMode = RequiredMode.REQUIRED) PartnerLedgerReason reason,
		@Schema(description = "시도한 횟수(관리자가 다시 반영하면 처음부터 센다)", requiredMode = RequiredMode.REQUIRED) int attempts,
		@Schema(description = "마지막으로 시도한 시각", requiredMode = RequiredMode.REQUIRED) Instant lastAttemptAt) {

	static PartnerLedgerSyncResponse from(PartnerLedgerSyncView view) {
		return new PartnerLedgerSyncResponse(view.id(), view.passId(), view.partnerId(), view.partnerName(), view.kind(),
				view.studentName(), view.reason(), view.attempts(), view.lastAttemptAt());
	}

}
