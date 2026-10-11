package com.jey.core.member;

import com.jey.core.member.domain.Grade;
import com.jey.core.member.domain.StudentStatus;

/**
 * 목록의 한 줄. 학부모 전체가 아니라 대표 연락처만 담는다(가린 번호를 만들려면 행마다 복호화해야 한다).
 */
public record StudentSummaryView(long id, long campusId, Long classId, String name, String school, Grade grade,
		StudentStatus status, StudentView.ContactView primaryContact) {
}
