package com.jey.core.member.domain;

import java.time.LocalDate;

import com.jey.core.shared.BaseEntity;
import com.jey.core.shared.ProtectedPhone;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 학생. 지우지 않고 {@link StudentStatus#WITHDRAWN}으로 남긴다. 청구와 패스 사용 내역이 학생을 참조한다.
 * 형제는 따로 갖지 않는다. 같은 학부모에 연결된 학생이 형제다(ADR-0023).
 */
@Entity
@Table(name = "core_student")
public class Student extends BaseEntity {

	public static final int MAX_NAME_LENGTH = 50;

	public static final int MAX_SCHOOL_LENGTH = 50;

	/**
	 * 등록일로 받는 범위. 날짜 입력칸에서 연도를 치다 만 값(0026-10-12)이 저장되는 것을 막는다.
	 * 미래 날짜는 막지 않는다(다음 달 등록을 미리 넣는다). 요청을 받는 쪽의 검증도 이 범위를 쓴다.
	 */
	public static final LocalDate MIN_REGISTERED_AT = LocalDate.of(2000, 1, 1);

	public static final LocalDate MAX_REGISTERED_AT = LocalDate.of(2099, 12, 31);

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "campus_id", nullable = false)
	private Long campusId;

	// 지금 듣는 반. 아직 반을 정하지 않은 학생은 비어 있다.
	// 한 달에 반을 나눠 듣는 것은 청구 라인이 반을 따로 골라 나타낸다.
	@Column(name = "class_id")
	private Long classId;

	@Column(nullable = false, length = MAX_NAME_LENGTH)
	private String name;

	@Column(length = MAX_SCHOOL_LENGTH)
	private String school;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Grade grade;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private StudentStatus status;

	@Column(name = "registered_at", nullable = false)
	private LocalDate registeredAt;

	// 본인 번호는 없을 수 있다. ProtectedPhone의 컬럼은 NOT NULL로 선언돼 있어 여기서 NULL 허용으로 바꾼다.
	// 번호가 없으면 네 컬럼이 모두 NULL이고, 읽으면 이 필드가 null이다.
	@Embedded
	@AttributeOverride(name = "ciphertext", column = @Column(name = "phone_enc", length = 100))
	@AttributeOverride(name = "fullIndex", column = @Column(name = "phone_bidx", length = 64))
	@AttributeOverride(name = "last4Index", column = @Column(name = "phone_last4_bidx", length = 64))
	@AttributeOverride(name = "keyVersion", column = @Column(name = "key_version"))
	private ProtectedPhone phone;

	protected Student() {
	}

	private Student(Long campusId, Long classId, String name, String school, Grade grade, LocalDate registeredAt,
			ProtectedPhone phone) {
		this.campusId = campusId;
		this.classId = classId;
		this.name = name;
		this.school = school;
		this.grade = grade;
		this.status = StudentStatus.ENROLLED;
		this.registeredAt = registeredAt;
		this.phone = phone;
	}

	/**
	 * @param classId 반을 아직 정하지 않았으면 {@code null}
	 * @param school 빈 값이면 {@code null}로 저장한다
	 * @param phone 본인 번호가 없으면 {@code null}
	 * @throws IllegalArgumentException 필수값이 비었거나, 이름·학교가 길거나, 등록일이 범위 밖일 때
	 */
	public static Student create(Long campusId, Long classId, String name, String school, Grade grade,
			LocalDate registeredAt, ProtectedPhone phone) {
		return new Student(required(campusId, "campusId"), classId, Names.valid(name, MAX_NAME_LENGTH),
				validSchool(school), required(grade, "grade"), validRegisteredAt(registeredAt), phone);
	}

	/**
	 * 전화번호를 뺀 정보를 통째로 바꾼다. 전화번호는 {@link #changePhone}, {@link #removePhone}으로 바꾼다.
	 *
	 * @throws IllegalArgumentException {@link #create}와 같은 조건, 또는 상태가 비었을 때
	 */
	public void update(Long campusId, Long classId, String name, String school, Grade grade, StudentStatus status,
			LocalDate registeredAt) {
		Long validCampusId = required(campusId, "campusId");
		String validName = Names.valid(name, MAX_NAME_LENGTH);
		String validSchool = validSchool(school);
		Grade validGrade = required(grade, "grade");
		StudentStatus validStatus = required(status, "status");
		LocalDate validRegisteredAt = validRegisteredAt(registeredAt);
		this.campusId = validCampusId;
		this.classId = classId;
		this.name = validName;
		this.school = validSchool;
		this.grade = validGrade;
		this.status = validStatus;
		this.registeredAt = validRegisteredAt;
	}

	/** 암호문과 인덱스를 통째로 바꿔 끼운다. */
	public void changePhone(ProtectedPhone phone) {
		this.phone = required(phone, "phone");
	}

	public void removePhone() {
		this.phone = null;
	}

	public boolean hasPhone() {
		return phone != null;
	}

	public Long getId() {
		return id;
	}

	public Long getCampusId() {
		return campusId;
	}

	public Long getClassId() {
		return classId;
	}

	public String getName() {
		return name;
	}

	public String getSchool() {
		return school;
	}

	public Grade getGrade() {
		return grade;
	}

	public StudentStatus getStatus() {
		return status;
	}

	public LocalDate getRegisteredAt() {
		return registeredAt;
	}

	/** 본인 번호가 없으면 {@code null}. */
	public ProtectedPhone getPhone() {
		return phone;
	}

	private static String validSchool(String school) {
		if (school == null || school.isBlank()) {
			return null;
		}
		String trimmed = school.strip();
		if (trimmed.length() > MAX_SCHOOL_LENGTH) {
			throw new IllegalArgumentException("school은 " + MAX_SCHOOL_LENGTH + "자를 넘을 수 없다");
		}
		return trimmed;
	}

	private static <T> T required(T value, String field) {
		if (value == null) {
			throw new IllegalArgumentException(field + "는 비울 수 없다");
		}
		return value;
	}

	/** 등록일로 받을 수 있는 날짜인지. */
	public static boolean isRegisteredAtInRange(LocalDate registeredAt) {
		return !registeredAt.isBefore(MIN_REGISTERED_AT) && !registeredAt.isAfter(MAX_REGISTERED_AT);
	}

	private static LocalDate validRegisteredAt(LocalDate registeredAt) {
		required(registeredAt, "registeredAt");
		if (!isRegisteredAtInRange(registeredAt)) {
			throw new IllegalArgumentException(
					"registeredAt은 " + MIN_REGISTERED_AT + "부터 " + MAX_REGISTERED_AT + " 사이여야 한다: " + registeredAt);
		}
		return registeredAt;
	}

}
