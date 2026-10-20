package com.jey.core.member;

import java.time.Instant;
import java.util.List;

import com.jey.TestcontainersConfiguration;
import com.jey.core.member.api.ContactInfo;
import com.jey.core.member.api.MemberDirectory;
import com.jey.core.member.api.StudentInfo;
import com.jey.core.member.domain.Grade;
import com.jey.core.member.domain.StudentStatus;
import com.jey.core.shared.PhoneNumber;
import com.jey.core.shared.PhoneProtector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.jey.core.member.MemberFixtures.DONGTAN;
import static com.jey.core.member.MemberFixtures.father;
import static com.jey.core.member.MemberFixtures.mother;
import static com.jey.core.member.MemberFixtures.randomPhone;
import static com.jey.core.member.MemberFixtures.registration;
import static com.jey.core.member.MemberFixtures.removeAllContacts;
import static com.jey.core.member.MemberFixtures.registrationWithPhone;
import static com.jey.core.member.MemberFixtures.uniqueName;
import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class MemberDirectoryTest {

	@Autowired
	MemberDirectory directory;

	@Autowired
	StudentService studentService;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PhoneProtector phoneProtector;

	@Test
	void 학생을_찾는다() {
		String name = uniqueName();
		StudentView created = studentService.register(registration(name, mother(randomPhone())));

		assertThat(directory.findStudent(created.id())).get()
				.usingRecursiveComparison()
				.ignoringFields("createdAt")
				.isEqualTo(new StudentInfo(created.id(), DONGTAN, null, name, true, null, "동탄고1"));
		assertThat(directory.findStudent(999_999_999L)).isEmpty();
	}

	// 패스 사용 처리가 "새로 만든 학생인가"를 이 값으로 본다. 고칠 수 있는 등록일도, 고칠 때마다 바뀌는 수정 시각도 아니다.
	// DB에는 한국 시각으로 적혀 있다(ADR-0017).
	@Test
	void 학생_자료를_만든_시각을_준다() {
		long id = studentService.register(registration(uniqueName(), mother(randomPhone()))).id();
		jdbc.update("UPDATE core_student SET created_at = '2026-01-02 03:04:05.123456', "
				+ "updated_at = '2026-05-06 07:08:09' WHERE id = ?", id);

		assertThat(directory.findStudent(id)).map(StudentInfo::createdAt)
			.contains(Instant.parse("2026-01-01T18:04:05.123456Z"));
	}

	@Test
	void 여러_학생을_한_번에_찾는다() {
		String firstName = uniqueName();
		long first = studentService.register(registration(firstName, mother(randomPhone()))).id();
		long second = studentService.register(registration(uniqueName(), mother(randomPhone()))).id();

		assertThat(directory.findStudents(List.of(first, second, 999_999_999L))).extracting(StudentInfo::studentId)
			.containsExactlyInAnyOrder(first, second);
		assertThat(directory.findStudents(List.of(first))).singleElement()
			.satisfies(found -> assertThat(found.name()).isEqualTo(firstName));
		assertThat(directory.findStudents(List.of())).isEmpty();
	}

	// 패스 사용 처리가 "이 학생의 학부모 중 패스를 받을 때 적은 번호의 학부모가 있는가"를 본다. 번호는 색인으로만 주고받는다.
	@Test
	void 학생의_학부모_중_그_번호의_학부모가_있는지_본다() {
		PhoneNumber fatherPhone = randomPhone();
		PhoneNumber motherPhone = randomPhone();
		PhoneNumber studentPhone = randomPhone();
		PhoneNumber otherPhone = randomPhone();
		long id = studentService
			.register(registrationWithPhone(uniqueName(), studentPhone, father(fatherPhone), mother(motherPhone)))
			.id();
		long other = studentService.register(registration(uniqueName(), mother(otherPhone))).id();

		assertThat(directory.hasGuardianWithPhoneIndex(id, phoneProtector.fullIndex(motherPhone))).isTrue();
		assertThat(directory.hasGuardianWithPhoneIndex(id, phoneProtector.fullIndex(fatherPhone))).isTrue();
		// 다른 학생의 학부모 번호, 학생 본인 번호는 아니다.
		assertThat(directory.hasGuardianWithPhoneIndex(id, phoneProtector.fullIndex(otherPhone))).isFalse();
		assertThat(directory.hasGuardianWithPhoneIndex(id, phoneProtector.fullIndex(studentPhone))).isFalse();
		assertThat(directory.hasGuardianWithPhoneIndex(other, phoneProtector.fullIndex(motherPhone))).isFalse();
		assertThat(directory.hasGuardianWithPhoneIndex(999_999_999L, phoneProtector.fullIndex(motherPhone))).isFalse();
	}

	// 이미 만든 청구가 퇴원한 학생을 참조한다. 새로 고를 수 있는지는 부르는 쪽이 enrolled로 판단한다.
	@Test
	void 퇴원한_학생도_돌려준다() {
		String name = uniqueName();
		StudentView created = studentService.register(registration(name, mother(randomPhone())));
		studentService.update(created.id(), new StudentChange(DONGTAN, null, name, null, Grade.HIGH_1,
				StudentStatus.WITHDRAWN, created.registeredAt(), null));

		assertThat(directory.findStudent(created.id())).map(StudentInfo::enrolled).contains(false);
	}

	@Test
	void 형제의_ID를_준다() {
		PhoneNumber phone = randomPhone();
		long first = studentService.register(registration(uniqueName(), mother(phone))).id();
		long second = studentService.register(registration(uniqueName(), mother(phone))).id();
		long alone = studentService.register(registration(uniqueName(), mother(randomPhone()))).id();

		assertThat(directory.siblingIdsOf(first)).containsExactly(second);
		assertThat(directory.siblingIdsOf(second)).containsExactly(first);
		assertThat(directory.siblingIdsOf(alone)).isEmpty();
		assertThat(directory.siblingIdsOf(999_999_999L)).isEmpty();
	}

	// 알림을 보내려면 원문이 필요하다. 그래도 문자열로 바꾸면 가린 표시다.
	@Test
	void 대표_연락처를_준다() {
		PhoneNumber motherPhone = randomPhone();
		PhoneNumber studentPhone = randomPhone();
		String name = uniqueName();
		long withMother = studentService.register(registration(uniqueName(), father(randomPhone()), mother(motherPhone)))
				.id();
		long alone = studentService.register(registrationWithPhone(name, studentPhone)).id();

		assertThat(directory.primaryContact(withMother)).contains(new ContactInfo("김어머니", false, motherPhone));
		assertThat(directory.primaryContact(alone)).contains(new ContactInfo(name, true, studentPhone));
		assertThat(directory.primaryContact(withMother).orElseThrow().phone().value()).isEqualTo(motherPhone.value());
		assertThat(directory.primaryContact(withMother).orElseThrow().toString()).doesNotContain(motherPhone.value())
				.contains(motherPhone.masked());
		assertThat(directory.primaryContact(999_999_999L)).isEmpty();
	}

	// 알림을 보내는 쪽은 빈 값이면 보낼 곳이 없는 것으로 다룬다. 예외가 나면 그 알림의 리스너가 계속 다시 실행된다.
	@Test
	void 연락처가_없는_학생의_대표_연락처는_비어_있다() {
		long id = studentService.register(registration(uniqueName(), mother(randomPhone()))).id();
		removeAllContacts(jdbc, id);

		assertThat(directory.primaryContact(id)).isEmpty();
		assertThat(directory.findStudent(id)).isPresent();
	}

}
