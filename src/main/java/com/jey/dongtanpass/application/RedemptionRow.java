package com.jey.dongtanpass.application;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 사용 내역의 한 줄.
 *
 * @param redeemedOn 사용일(한국 날짜). 기간 조건은 이 날짜로 자른다
 * @param studentName 학생 이름. 학생을 찾지 못했으면(있어서는 안 되는 자료) {@code null}
 * @param staffId 처리한 직원. 로그인 없이 넣은 기록이면 {@code null}
 */
public record RedemptionRow(long redemptionId, Instant redeemedAt, LocalDate redeemedOn, long campusId, long passId,
		String passNo, String partnerName, long studentId, String studentName, Long staffId, Long invoiceId) {
}
