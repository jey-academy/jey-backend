package com.jey.core.member;

import java.time.LocalDate;
import java.util.List;

import com.jey.core.member.domain.Grade;
import com.jey.core.shared.PhoneNumber;

/**
 * 학생 등록에 필요한 값.
 *
 * @param classId 반을 아직 정하지 않았으면 {@code null}
 * @param registeredAt {@code null}이면 오늘
 * @param phone 학생 본인 번호. 없으면 {@code null}
 * @param guardians 없으면 {@code null}이나 빈 목록. 그때는 {@code phone}이 있어야 한다
 */
public record StudentRegistration(long campusId, Long classId, String name, String school, Grade grade,
		LocalDate registeredAt, PhoneNumber phone, List<GuardianInput> guardians) {

	public StudentRegistration {
		guardians = (guardians == null) ? List.of() : List.copyOf(guardians);
	}

}
