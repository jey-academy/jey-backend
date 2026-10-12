package com.jey.core.member.domain;

import com.jey.core.shared.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 학생과 학부모의 연결. 관계(어머니, 아버지 등)는 학부모가 아니라 이 연결이 갖는다.
 * 같은 사람이 한 학생에게는 어머니이고 다른 학생에게는 다른 관계일 수 있다.
 * ID가 작을수록 먼저 연결된 것이다.
 */
@Entity
@Table(name = "core_student_guardian")
public class StudentGuardian extends BaseEntity {

	/** 학생 한 명에게 연결할 수 있는 학부모 수. 등록할 때와 나중에 추가할 때 모두 이 값을 넘지 못한다. */
	public static final int MAX_PER_STUDENT = 4;


	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "student_id", nullable = false, updatable = false)
	private Long studentId;

	@Column(name = "guardian_id", nullable = false, updatable = false)
	private Long guardianId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private GuardianRelation relation;

	protected StudentGuardian() {
	}

	private StudentGuardian(Long studentId, Long guardianId, GuardianRelation relation) {
		this.studentId = studentId;
		this.guardianId = guardianId;
		this.relation = relation;
	}

	/**
	 * @throws IllegalArgumentException 값이 비었을 때
	 */
	public static StudentGuardian link(Long studentId, Long guardianId, GuardianRelation relation) {
		if (studentId == null || guardianId == null || relation == null) {
			throw new IllegalArgumentException("studentId, guardianId, relation은 비울 수 없다");
		}
		return new StudentGuardian(studentId, guardianId, relation);
	}

	/**
	 * @throws IllegalArgumentException 값이 비었을 때
	 */
	public void changeRelation(GuardianRelation relation) {
		if (relation == null) {
			throw new IllegalArgumentException("relation은 비울 수 없다");
		}
		this.relation = relation;
	}

	public Long getId() {
		return id;
	}

	public Long getStudentId() {
		return studentId;
	}

	public Long getGuardianId() {
		return guardianId;
	}

	public GuardianRelation getRelation() {
		return relation;
	}

}
