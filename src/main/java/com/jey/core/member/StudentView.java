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
 * @param primaryContact 대표 연락처. 어머니, 먼저 연결된 학부모, 학생 본인 번호 순으로 고른다.
 * 연락처가 하나도 없는 학생이면 {@code null}이다(있어서는 안 되는 자료지만, 그런 학생도 보여는 준다)
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
	 * {@link #ofGuardian}이나 {@link #ofStudent}로 만든다.
	 *
	 * @param relation 학생 본인 번호면 {@code null}. 본인 번호인지는 {@link #studentOwn()}으로 확인한다
	 */
	public record ContactView(String name, GuardianRelation relation, PhoneNumber phone) {

		public static ContactView ofGuardian(String name, GuardianRelation relation, PhoneNumber phone) {
			if (relation == null) {
				throw new IllegalArgumentException("학부모의 연락처에는 relation이 있어야 한다");
			}
			return new ContactView(name, relation, phone);
		}

		/** 학부모가 없어서 학생 본인 번호를 대표로 쓰는 경우. */
		public static ContactView ofStudent(String studentName, PhoneNumber phone) {
			return new ContactView(studentName, null, phone);
		}

		public boolean studentOwn() {
			return relation == null;
		}

	}

	public record SiblingView(long id, String name, StudentStatus status) {
	}

}
