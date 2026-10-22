package com.jey.billing.application;

import java.time.Instant;
import java.time.YearMonth;

import com.jey.billing.domain.LedgerSync;
import com.jey.billing.domain.LedgerSyncReason;
import com.jey.billing.domain.LedgerSyncStatus;

/**
 * 결제 하나의 장부 반영 상태. 전화번호는 없다.
 *
 * @param studentName 학생 이름. 학생을 찾지 못하면 {@code null}(있어서는 안 되는 자료)
 */
public record LedgerSyncView(long id, long paymentId, long studentId, String studentName, long campusId,
		YearMonth targetMonth, LedgerSyncStatus status, LedgerSyncReason reason, int attempts, Instant lastAttemptAt) {

	static LedgerSyncView of(LedgerSync sync, String studentName) {
		return new LedgerSyncView(sync.getId(), sync.getPaymentId(), sync.getStudentId(), studentName, sync.getCampusId(),
				sync.getTargetMonth(), sync.getStatus(), sync.getReason(), sync.getAttempts(), sync.getLastAttemptAt());
	}

}
