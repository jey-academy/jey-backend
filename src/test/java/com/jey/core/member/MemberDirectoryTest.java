package com.jey.core.member;

import com.jey.TestcontainersConfiguration;
import com.jey.core.member.api.ContactInfo;
import com.jey.core.member.api.MemberDirectory;
import com.jey.core.member.api.StudentInfo;
import com.jey.core.member.domain.Grade;
import com.jey.core.member.domain.StudentStatus;
import com.jey.core.shared.PhoneNumber;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static com.jey.core.member.MemberFixtures.DONGTAN;
import static com.jey.core.member.MemberFixtures.father;
import static com.jey.core.member.MemberFixtures.mother;
import static com.jey.core.member.MemberFixtures.randomPhone;
import static com.jey.core.member.MemberFixtures.registration;
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

	@Test
	void 학생을_찾는다() {
		String name = uniqueName();
		StudentView created = studentService.register(registration(name, mother(randomPhone())));

		assertThat(directory.findStudent(created.id()))
				.contains(new StudentInfo(created.id(), DONGTAN, null, name, true));
		assertThat(directory.findStudent(999_999_999L)).isEmpty();
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

}
