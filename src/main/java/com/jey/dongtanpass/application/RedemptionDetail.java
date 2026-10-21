package com.jey.dongtanpass.application;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 패스 사용 기록 하나(데스크의 청구 화면이 학생과 지점을 읽는다).
 *
 * @param campusId 사용한 지점(권한 확인 기준)
 * @param studentName 학생을 찾지 못하면 {@code null}(있어서는 안 되는 자료)
 * @param studentCampusId 학생의 지금 지점. 반 목록과 청구는 이 지점 기준이다. 학생을 찾지 못하면 {@code null}
 * @param invoiceId 이 사용 기록에서 만든 열린 청구(결제 전이거나 결제함). 만들지 않았거나 취소했으면 {@code null}
 */
public record RedemptionDetail(long redemptionId, Instant redeemedAt, LocalDate redeemedOn, long campusId, long passId,
		String passNo, String partnerName, long studentId, String studentName, Long studentCampusId, Long invoiceId) {
}
