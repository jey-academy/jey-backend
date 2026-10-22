package com.jey.dongtanpass.application;

import java.time.Instant;

import com.jey.dongtanpass.domain.PartnerLedgerKind;
import com.jey.dongtanpass.domain.PartnerLedgerReason;
import com.jey.dongtanpass.domain.PartnerLedgerStatus;
import com.jey.dongtanpass.domain.PartnerLedgerSync;

/**
 * 패스 사용 하나의 연계 학원 장부 반영 상태. 전화번호는 없다.
 *
 * @param partnerName 연계 학원 이름. 학원을 찾지 못하면 {@code null}(있어서는 안 되는 자료)
 * @param studentName 패스의 학생 이름(발급 때의 이름). 패스를 찾지 못하면 {@code null}
 */
public record PartnerLedgerSyncView(long id, long passId, long partnerId, String partnerName, PartnerLedgerKind kind,
		String studentName, PartnerLedgerStatus status, PartnerLedgerReason reason, int attempts, Instant lastAttemptAt) {

	static PartnerLedgerSyncView of(PartnerLedgerSync sync, String partnerName, String studentName) {
		return new PartnerLedgerSyncView(sync.getId(), sync.getPassId(), sync.getPartnerId(), partnerName, sync.getKind(),
				studentName, sync.getStatus(), sync.getReason(), sync.getAttempts(), sync.getLastAttemptAt());
	}

}
