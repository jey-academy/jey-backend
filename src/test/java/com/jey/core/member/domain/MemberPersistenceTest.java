package com.jey.core.member.domain;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import com.jey.TestcontainersConfiguration;
import com.jey.core.shared.PhoneNumber;
import com.jey.core.shared.PhoneProtector;
import com.jey.core.shared.UniqueKeys;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ProtectedPhone을 엔티티에 @Embedded로 넣은 첫 자리다. 매핑이 실제 DB에서 도는지 본다.
// 테스트끼리 DB를 같이 쓰므로 전화번호는 매번 새로 만든다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class MemberPersistenceTest {

	private static final LocalDate REGISTERED = LocalDate.of(2026, 10, 11);

	@Autowired
	GuardianRepository guardians;

	@Autowired
	StudentRepository students;

	@Autowired
	StudentGuardianRepository links;

	@Autowired
	PhoneProtector protector;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	EntityManager entityManager;

	@Autowired
	TransactionTemplate transaction;

	@Test
	void 학부모를_저장하고_다시_읽어_되돌리면_원래_번호다() {
		PhoneNumber phone = randomPhone();
		Long id = guardians.saveAndFlush(Guardian.create("김학부모", protector.protect(phone))).getId();

		Guardian read = readFresh(Guardian.class, id);

		assertThat(protector.reveal(read.getPhone())).isEqualTo(phone);
		assertThat(guardians.findByPhoneIndex(protector.fullIndex(phone))).map(Guardian::getId).contains(id);
	}

	@Test
	void DB의_어느_컬럼에도_전화번호_원문이_없다() {
		PhoneNumber phone = randomPhone();
		Long guardianId = guardians.saveAndFlush(Guardian.create("김학부모", protector.protect(phone))).getId();
		Long studentId = students
				.saveAndFlush(Student.create(1L, null, "김학생", null, Grade.HIGH_1, REGISTERED, protector.protect(phone)))
				.getId();

		Map<String, Object> guardianRow = jdbc.queryForMap("SELECT * FROM core_guardian WHERE id = ?", guardianId);
		Map<String, Object> studentRow = jdbc.queryForMap("SELECT * FROM core_student WHERE id = ?", studentId);

		assertThat(guardianRow.values().toString() + studentRow.values())
				.doesNotContain(phone.value())
				.doesNotContain(phone.value().substring(3));
		assertThat(guardianRow.get("phone_enc")).isNotNull();
		assertThat(studentRow.get("phone_last4_bidx")).isEqualTo(protector.last4Index(phone.last4()));
	}

	// 본인 번호가 없는 학생은 네 컬럼이 모두 NULL이다. 읽었을 때 빈 덩어리가 아니라 null이어야 "번호 없음"으로 다룰 수 있다.
	@Test
	void 번호_없는_학생을_저장하고_읽으면_번호가_null이다() {
		Long id = students.saveAndFlush(Student.create(1L, null, "김학생", null, Grade.HIGH_1, REGISTERED, null))
				.getId();

		Student read = readFresh(Student.class, id);

		assertThat(read.getPhone()).isNull();
		assertThat(read.hasPhone()).isFalse();
		assertThat(jdbc.queryForMap("SELECT phone_enc, phone_bidx, phone_last4_bidx, key_version FROM core_student "
				+ "WHERE id = ?", id).values()).containsOnlyNulls();
	}

	@Test
	void 학생의_번호를_넣었다가_지우면_네_컬럼이_모두_비워진다() {
		PhoneNumber phone = randomPhone();
		Student student = students
				.saveAndFlush(Student.create(1L, null, "김학생", "동탄고", Grade.HIGH_1, REGISTERED, protector.protect(phone)));
		assertThat(protector.reveal(readFresh(Student.class, student.getId()).getPhone())).isEqualTo(phone);

		student.removePhone();
		students.saveAndFlush(student);

		assertThat(readFresh(Student.class, student.getId()).getPhone()).isNull();
		assertThat(jdbc.queryForMap("SELECT phone_enc, phone_bidx, phone_last4_bidx, key_version FROM core_student "
				+ "WHERE id = ?", student.getId()).values()).containsOnlyNulls();
	}

	@Test
	void 학생의_값이_그대로_왕복된다() {
		Long id = students.saveAndFlush(Student.create(2L, null, "김학생", "능동고", Grade.MIDDLE_3, REGISTERED, null))
				.getId();

		Student read = readFresh(Student.class, id);

		assertThat(read.getCampusId()).isEqualTo(2L);
		assertThat(read.getSchool()).isEqualTo("능동고");
		assertThat(read.getGrade()).isEqualTo(Grade.MIDDLE_3);
		assertThat(read.getStatus()).isEqualTo(StudentStatus.ENROLLED);
		assertThat(read.getRegisteredAt()).isEqualTo(REGISTERED);
		assertThat(read.getCreatedAt()).isNotNull();
	}

	// 전화번호 하나에 학부모는 한 명이다. 동시에 등록할 때 이것을 지키는 것은 유니크 키뿐이다.
	@Test
	void 같은_번호의_학부모는_둘_저장되지_않는다() {
		PhoneNumber phone = randomPhone();
		guardians.saveAndFlush(Guardian.create("김학부모", protector.protect(phone)));

		assertThatThrownBy(() -> guardians.saveAndFlush(Guardian.create("이학부모", protector.protect(phone))))
				.isInstanceOfSatisfying(DataIntegrityViolationException.class,
						ex -> assertThat(UniqueKeys.isViolationOf(ex, "uk_core_guardian_phone")).isTrue());
	}

	// 학부모와 번호를 같이 쓰는 학생이 있다.
	@Test
	void 학생은_학부모나_다른_학생과_번호가_같아도_된다() {
		PhoneNumber phone = randomPhone();
		guardians.saveAndFlush(Guardian.create("김학부모", protector.protect(phone)));

		students.saveAndFlush(Student.create(1L, null, "김첫째", null, Grade.HIGH_1, REGISTERED, protector.protect(phone)));
		students.saveAndFlush(Student.create(1L, null, "김둘째", null, Grade.MIDDLE_1, REGISTERED, protector.protect(phone)));
	}

	@Test
	void 같은_학생과_학부모를_두_번_연결할_수_없다() {
		Long guardianId = guardians.saveAndFlush(Guardian.create("김학부모", protector.protect(randomPhone()))).getId();
		Long studentId = students
				.saveAndFlush(Student.create(1L, null, "김학생", null, Grade.HIGH_1, REGISTERED, null))
				.getId();
		links.saveAndFlush(StudentGuardian.link(studentId, guardianId, GuardianRelation.MOTHER));

		assertThatThrownBy(
				() -> links.saveAndFlush(StudentGuardian.link(studentId, guardianId, GuardianRelation.OTHER)))
				.isInstanceOfSatisfying(DataIntegrityViolationException.class,
						ex -> assertThat(UniqueKeys.isViolationOf(ex, "uk_core_student_guardian")).isTrue());
	}

	@Test
	void 없는_지점이나_반으로는_학생이_저장되지_않는다() {
		assertThatThrownBy(() -> students
				.saveAndFlush(Student.create(999_999L, null, "김학생", null, Grade.HIGH_1, REGISTERED, null)))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> students
				.saveAndFlush(Student.create(1L, 999_999_999L, "김학생", null, Grade.HIGH_1, REGISTERED, null)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	// 저장할 때 쓴 객체가 아니라 DB에서 새로 읽은 것을 본다.
	private <T> T readFresh(Class<T> type, Long id) {
		return transaction.execute(status -> {
			entityManager.clear();
			return entityManager.find(type, id);
		});
	}

	static PhoneNumber randomPhone() {
		return PhoneNumber.of("010" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000)));
	}

}
