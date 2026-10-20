package com.jey.billing.application;

import java.time.Instant;
import java.time.YearMonth;

import com.jey.billing.domain.LedgerSync;
import com.jey.billing.domain.LedgerSyncReason;
import com.jey.billing.domain.LedgerSyncStatus;

/** 결제 하나의 장부 반영 상태. 이름·전화번호는 없다(학생은 ID로만). */
public record LedgerSyncView(long id, long paymentId, long studentId, long campusId, YearMonth targetMonth,
		LedgerSyncStatus status, LedgerSyncReason reason, int attempts, Instant lastAttemptAt) {

	static LedgerSyncView of(LedgerSync sync) {
		return new LedgerSyncView(sync.getId(), sync.getPaymentId(), sync.getStudentId(), sync.getCampusId(),
				sync.getTargetMonth(), sync.getStatus(), sync.getReason(), sync.getAttempts(), sync.getLastAttemptAt());
	}

}
