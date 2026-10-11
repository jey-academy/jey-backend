package com.jey.core.member;

import java.time.LocalDate;
import java.util.List;

import com.jey.core.member.domain.Grade;
import com.jey.core.member.domain.GuardianRelation;
import com.jey.core.member.domain.StudentStatus;
import com.jey.core.shared.PhoneNumber;

/**
 * 학생 한 명의 전체 정보. 전화번호는 {@link PhoneNumber}라서 응답이나 로그로 나가면 가린 표시가 된다.
 *
 * @param phone 학생 본인 번호. 없으면 {@code null}
 * @param guardians 먼저 연결된 순서
 * @param primaryContact 대표 연락처. 어머니, 먼저 연결된 학부모, 학생 본인 번호 순으로 고른다
 * @param siblings 같은 학부모에 연결된 다른 학생
 */
public record StudentView(long id, long campusId, Long classId, String name, String school, Grade grade,
		StudentStatus status, LocalDate registeredAt, PhoneNumber phone, List<GuardianView> guardians,
		ContactView primaryContact, List<SiblingView> siblings) {

	/**
	 * @param linkedToExisting 이번 요청에서 새로 만들지 않고 이미 있던 학부모에 붙였는지. 조회에서는 항상 {@code false}
	 */
	public record GuardianView(long id, String name, GuardianRelation relation, PhoneNumber phone,
			boolean linkedToExisting) {
	}

	/**
	 * @param relation 학생 본인 번호면 {@code null}
	 */
	public record ContactView(String name, GuardianRelation relation, PhoneNumber phone) {
	}

	public record SiblingView(long id, String name, StudentStatus status) {
	}

}
