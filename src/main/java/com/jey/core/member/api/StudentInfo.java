package com.jey.core.member.api;

import java.time.Instant;

/**
 * 학생. 연락처는 담지 않는다({@link ContactInfo}).
 *
 * @param classId 지금 듣는 반. 정하지 않았으면 {@code null}
 * @param enrolled 재원 중인지. 퇴원한 학생은 {@code false}
 * @param createdAt 학생 자료를 만든 시각. null이 아니고 고칠 수 없다(직원이 고칠 수 있는 등록일과 다르다)
 * @param schoolLabel 학교와 학년 숫자({@code 동탄고1}, 학년이 기타면 학교만). 학교를 적지 않았으면 {@code null}
 */
public record StudentInfo(long studentId, long campusId, Long classId, String name, boolean enrolled,
		Instant createdAt, String schoolLabel) {
}
