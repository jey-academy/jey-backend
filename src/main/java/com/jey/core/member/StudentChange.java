package com.jey.core.member;

import java.time.LocalDate;

import com.jey.core.member.domain.Grade;
import com.jey.core.member.domain.StudentStatus;
import com.jey.core.shared.PhoneNumber;

/**
 * 학생 정보 수정. 전화번호를 뺀 값은 통째로 바꾼다.
 *
 * @param classId 반이 없으면 {@code null}
 * @param phone 새 번호. {@code null}이면 그대로 둔다(화면은 가린 번호만 갖고 있어 원래 번호를 다시 보낼 수 없다).
 * 번호를 지우는 것은 {@link StudentService#removePhone}이다
 */
public record StudentChange(long campusId, Long classId, String name, String school, Grade grade,
		StudentStatus status, LocalDate registeredAt, PhoneNumber phone) {
}
