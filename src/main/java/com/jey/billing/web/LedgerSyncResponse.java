package com.jey.billing.web;

import java.time.Instant;
import java.time.YearMonth;

import com.jey.billing.application.LedgerSyncView;
import com.jey.billing.domain.LedgerSyncReason;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "장부에 반영하지 못한 결제 하나. 전화번호는 싣지 않는다")
record LedgerSyncResponse(@Schema(description = "장부 반영 ID. 다시 반영할 때 쓴다", requiredMode = RequiredMode.REQUIRED) long id,
		@Schema(requiredMode = RequiredMode.REQUIRED) long paymentId,
		@Schema(requiredMode = RequiredMode.REQUIRED) long studentId,
		@Schema(description = "학생 이름. 학생을 찾지 못하면 null(있어서는 안 되는 자료)", requiredMode = RequiredMode.REQUIRED,
				nullable = true) String studentName,
		@Schema(requiredMode = RequiredMode.REQUIRED) long campusId,
		@Schema(description = "청구의 대상 월(YYYY-MM). 장부의 탭 이름과 같다", type = "string", example = "2026-11",
				requiredMode = RequiredMode.REQUIRED) YearMonth targetMonth,
		@Schema(description = LedgerSyncDocs.REASON, requiredMode = RequiredMode.REQUIRED) LedgerSyncReason reason,
		@Schema(description = "시도한 횟수(관리자가 다시 반영하면 처음부터 센다)", requiredMode = RequiredMode.REQUIRED) int attempts,
		@Schema(description = "마지막으로 시도한 시각", requiredMode = RequiredMode.REQUIRED) Instant lastAttemptAt) {

	static LedgerSyncResponse from(LedgerSyncView view) {
		return new LedgerSyncResponse(view.id(), view.paymentId(), view.studentId(), view.studentName(), view.campusId(), view.targetMonth(),
				view.reason(), view.attempts(), view.lastAttemptAt());
	}

}
