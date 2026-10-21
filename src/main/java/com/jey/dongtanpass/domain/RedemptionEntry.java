package com.jey.dongtanpass.domain;

import java.time.Instant;

/**
 * 사용 내역의 한 줄. 사용 기록에 패스 번호와 연계 학원 이름을 붙인 것이다(같은 모듈의 테이블만 묶는다). 학생 이름은 없다.
 *
 * @param staffId 처리한 직원(사용 기록의 {@code created_by}). 로그인 없이 넣은 기록이면 {@code null}
 */
public record RedemptionEntry(Long redemptionId, Instant redeemedAt, Long campusId, Long passId, String passNo,
		String partnerName, Long studentId, Long staffId, Long invoiceId) {
}
