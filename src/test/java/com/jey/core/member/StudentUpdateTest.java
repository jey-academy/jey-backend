package com.jey.core.member;

import java.time.LocalDate;

import com.jey.TestcontainersConfiguration;
import com.jey.core.campus.ClassService;
import com.jey.core.campus.domain.AcademyClass;
import com.jey.core.member.GuardianService.GuardianProfile;
import com.jey.core.member.StudentView.GuardianView;
import com.jey.core.member.domain.Grade;
import com.jey.core.member.domain.GuardianRelation;
import com.jey.core.member.domain.GuardianRepository;
import com.jey.core.member.domain.StudentStatus;
import com.jey.core.shared.Money;
import com.jey.core.shared.PhoneNumber;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static com.jey.core.member.MemberFixtures.DONGTAN;
import static com.jey.core.member.MemberFixtures.NEUNGDONG;
import static com.jey.core.member.MemberFixtures.father;
import static com.jey.core.member.MemberFixtures.mother;
import static com.jey.core.member.MemberFixtures.randomLast4;
import static com.jey.core.member.MemberFixtures.randomPhone;
import static com.jey.core.member.MemberFixtures.randomPhoneEndingWith;
import static com.jey.core.member.MemberFixtures.registration;
import static com.jey.core.member.MemberFixtures.registrationWithPhone;
import static com.jey.core.member.MemberFixtures.uniqueName;
import static com.jey.core.member.StudentRegistrationTest.assertFails;
import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class StudentUpdateTest {

	private static final LocalDate REGISTERED = LocalDate.of(2026, 3, 2);

	@Autowired
	StudentService studentService;

	@Autowired
	GuardianService guardianService;

	@Autowired
	ClassService classService;

	@Autowired
	GuardianRepository guardians;

	// 두 직원이 같은 학생 화면을 열어 두고 A는 반을, B는 학교를 고친다. 서버의 행 잠금은 두 저장을 줄 세울 뿐이라,
	// 버전을 보지 않으면 B의 저장이 화면에 남아 있던 옛 반을 다시 써서 A의 변경이 조용히 사라진다.
	@Test
	void 같은_화면에서_고친_두_번째_저장은_거부되고_앞사람의_변경이_남는다() {
		AcademyClass aClass = newClass(DONGTAN);
		StudentView screen = studentService.register(registration(uniqueName(), mother(randomPhone())));

		studentService.update(screen.id(), change(screen, DONGTAN, aClass.getId(), null));
		assertFails(() -> studentService.update(screen.id(), new StudentChange(screen.version(), DONGTAN,
				screen.classId(), screen.name(), "능동고", screen.grade(), screen.status(), screen.registeredAt(), null)),
				MemberErrorCode.STUDENT_CHANGED);

		StudentView after = studentService.get(screen.id());
		assertThat(after.classId()).isEqualTo(aClass.getId());
		assertThat(after.school()).isEqualTo(screen.school());
	}

	@Test
	void 수정할_때마다_버전이_오르고_응답의_버전으로_다시_고칠_수_있다() {
		StudentView created = studentService.register(registration(uniqueName(), mother(randomPhone())));
		assertThat(created.version()).isZero();

		StudentView first = studentService.update(created.id(), change(created, DONGTAN, null, null, "능동고"));
		StudentView second = studentService.update(created.id(), change(first, DONGTAN, null, null, "동탄중"));

		assertThat(first.version()).isEqualTo(1);
		assertThat(second.version()).isEqualTo(2);
		assertThat(studentService.get(created.id()).version()).isEqualTo(2);
	}

	// 바뀐 칸이 없으면 UPDATE가 나가지 않아 버전이 그대로다. 같은 화면에서 저장을 두 번 눌러도 거부되지 않는다.
	@Test
	void 바뀐_값이_없는_저장은_버전을_올리지_않는다() {
		StudentView created = studentService.register(registration(uniqueName(), mother(randomPhone())));

		StudentView first = studentService.update(created.id(), change(created, DONGTAN, null, null));
		StudentView again = studentService.update(created.id(), change(created, DONGTAN, null, null));

		assertThat(first.version()).isEqualTo(created.version());
		assertThat(again.version()).isEqualTo(created.version());
	}

	@Test
	void 번호를_지우면_버전이_올라_그_전_화면의_저장은_거부된다() {
		StudentView screen = studentService.register(
				registrationWithPhone(uniqueName(), randomPhone(), mother(randomPhone())));

		studentService.removePhone(screen.id());

		assertThat(studentService.get(screen.id()).version()).isGreaterThan(screen.version());
		assertFails(() -> studentService.update(screen.id(), change(screen, DONGTAN, null, null, "능동고")),
				MemberErrorCode.STUDENT_CHANGED);
	}

	// 학부모 연결은 수정(PUT)이 덮어쓰는 칸이 아니다. 학부모를 붙인 뒤 열어 둔 수정 화면이 괜히 거부되지 않는다.
	@Test
	void 학부모를_붙이거나_관계를_바꿔도_버전은_그대로다() {
		StudentView screen = studentService.register(registration(uniqueName(), mother(randomPhone())));

		StudentView linked = studentService.addGuardian(screen.id(), father(randomPhone()));
		long fatherId = linked.guardians().getLast().id();
		studentService.changeRelation(screen.id(), fatherId, GuardianRelation.OTHER);

		assertThat(studentService.get(screen.id()).version()).isEqualTo(screen.version());
		assertThat(studentService.update(screen.id(), change(screen, DONGTAN, null, null, "능동고")).school())
			.isEqualTo("능동고");
	}

	// 낡은 화면의 값으로 다른 오류(반이 틀렸다 등)를 내지 않는다. 먼저 다시 불러오게 한다.
	@Test
	void 낡은_버전이면_지점이나_반이_틀려도_먼저_버전으로_거부한다() {
		StudentView screen = studentService.register(registration(uniqueName(), mother(randomPhone())));
		studentService.update(screen.id(), change(screen, DONGTAN, null, null, "능동고"));

		assertFails(() -> studentService.update(screen.id(), change(screen, DONGTAN, 999_999_999L, null)),
				MemberErrorCode.STUDENT_CHANGED);
	}

	@Test
	void 학생_정보를_고친다() {
		AcademyClass neungdongClass = newClass(NEUNGDONG);
		StudentView created = studentService.register(registration(uniqueName(), mother(randomPhone())));
		String renamed = uniqueName();

		StudentView updated = studentService.update(created.id(), new StudentChange(created.version(), NEUNGDONG, neungdongClass.getId(),
				" " + renamed + " ", "능동고", Grade.HIGH_2, StudentStatus.WITHDRAWN, REGISTERED, null));

		assertThat(updated.campusId()).isEqualTo(NEUNGDONG);
		assertThat(updated.classId()).isEqualTo(neungdongClass.getId());
		assertThat(updated.name()).isEqualTo(renamed);
		assertThat(updated.school()).isEqualTo("능동고");
		assertThat(updated.grade()).isEqualTo(Grade.HIGH_2);
		assertThat(updated.status()).isEqualTo(StudentStatus.WITHDRAWN);
		assertThat(updated.registeredAt()).isEqualTo(REGISTERED);
		assertThat(studentService.get(created.id())).isEqualTo(updated);
	}

	// 화면은 가린 번호만 갖고 있어서 원래 번호를 다시 보낼 수 없다. 번호를 안 보내면 그대로 둔다.
	@Test
	void 번호를_보내지_않으면_그대로_두고_보내면_바꾼다() {
		PhoneNumber original = randomPhone();
		PhoneNumber changed = randomPhone();
		StudentView created = studentService.register(registrationWithPhone(uniqueName(), original, mother(randomPhone())));

		assertThat(studentService.update(created.id(), change(created, DONGTAN, null, null)).phone())
				.isEqualTo(original);
		assertThat(studentService.update(created.id(), change(created, DONGTAN, null, changed)).phone())
				.isEqualTo(changed);
		assertThat(studentService.get(created.id()).phone()).isEqualTo(changed);
	}

	@Test
	void 학생_번호를_바꾸면_새_뒷자리로만_찾아진다() {
		String oldLast4 = randomLast4();
		String newLast4 = String.format("%04d", (Integer.parseInt(oldLast4) + 1) % 10_000);
		String name = uniqueName();
		// 어머니 번호의 뒷자리가 우연히 같으면 "옛 뒷자리로 안 찾아진다"가 깨진다. 다른 뒷자리로 고정한다.
		String motherLast4 = String.format("%04d", (Integer.parseInt(oldLast4) + 2) % 10_000);
		StudentView created = studentService.register(registrationWithPhone(name, randomPhoneEndingWith(oldLast4),
				mother(randomPhoneEndingWith(motherLast4))));

		studentService.update(created.id(), change(created, DONGTAN, null, randomPhoneEndingWith(newLast4)));

		assertThat(studentService.search(null, name, newLast4, 0, 20)).hasSize(1);
		assertThat(studentService.search(null, name, oldLast4, 0, 20)).isEmpty();
	}

	@Test
	void 지점을_바꾸면서_반을_그대로_두면_고칠_수_없다() {
		AcademyClass dongtanClass = newClass(DONGTAN);
		StudentView created = studentService.register(registration(uniqueName(), mother(randomPhone())));
		StudentView placed = studentService.update(created.id(), change(created, DONGTAN, dongtanClass.getId(), null));

		assertFails(() -> studentService.update(created.id(), change(placed, NEUNGDONG, dongtanClass.getId(), null)),
				MemberErrorCode.INVALID_CLASS);
		assertFails(() -> studentService.update(created.id(), change(placed, 999_999, null, null)),
				MemberErrorCode.INVALID_CAMPUS);
		assertFails(() -> studentService.update(created.id(), change(placed, DONGTAN, 999_999_999L, null)),
				MemberErrorCode.INVALID_CLASS);

		assertThat(studentService.get(created.id()).campusId()).isEqualTo(DONGTAN);
		assertThat(studentService.get(created.id()).classId()).isEqualTo(dongtanClass.getId());
	}

	// 반을 끈 뒤에도 그 반에 있던 학생의 다른 정보는 고칠 수 있어야 한다. 꺼진 반으로 새로 옮기는 것만 막는다.
	@Test
	void 꺼진_반에_있던_학생은_반을_그대로_둔_채_고칠_수_있다() {
		AcademyClass closing = newClass(DONGTAN);
		StudentView inClass = studentService.register(registration(uniqueName(), mother(randomPhone())));
		StudentView outside = studentService.register(registration(uniqueName(), mother(randomPhone())));
		StudentView placed = studentService.update(inClass.id(), change(inClass, DONGTAN, closing.getId(), null));
		classService.update(closing.getId(), closing.getName(), Money.of(280_000), Money.ZERO, false);

		assertThat(studentService.update(inClass.id(), change(placed, DONGTAN, closing.getId(), null)).classId())
				.isEqualTo(closing.getId());
		assertFails(() -> studentService.update(outside.id(), change(outside, DONGTAN, closing.getId(), null)),
				MemberErrorCode.INVALID_CLASS);
	}

	@Test
	void 없는_학생은_고칠_수_없다() {
		StudentView any = studentService.register(registration(uniqueName(), mother(randomPhone())));

		assertFails(() -> studentService.update(999_999_999L, change(any, DONGTAN, null, null)),
				MemberErrorCode.STUDENT_NOT_FOUND);
		assertFails(() -> studentService.removePhone(999_999_999L), MemberErrorCode.STUDENT_NOT_FOUND);
		assertFails(() -> studentService.addGuardian(999_999_999L, mother(randomPhone())),
				MemberErrorCode.STUDENT_NOT_FOUND);
		assertFails(() -> studentService.removeGuardian(999_999_999L, 1), MemberErrorCode.STUDENT_NOT_FOUND);
	}

	@Test
	void 학생_번호를_지운다() {
		String last4 = randomLast4();
		String name = uniqueName();
		String motherLast4 = String.format("%04d", (Integer.parseInt(last4) + 1) % 10_000);
		StudentView created = studentService.register(registrationWithPhone(name, randomPhoneEndingWith(last4),
				mother(randomPhoneEndingWith(motherLast4))));

		studentService.removePhone(created.id());

		assertThat(studentService.get(created.id()).phone()).isNull();
		assertThat(studentService.search(null, name, last4, 0, 20)).isEmpty();
		// 원래 없으면 아무 일도 없다.
		studentService.removePhone(created.id());
	}

	// 대표 연락처가 없는 학생이 생기면 알림을 보낼 곳이 없다.
	@Test
	void 마지막_연락처는_지울_수_없다() {
		PhoneNumber studentPhone = randomPhone();
		StudentView onlyPhone = studentService.register(registrationWithPhone(uniqueName(), studentPhone));
		StudentView onlyGuardian = studentService.register(registration(uniqueName(), mother(randomPhone())));
		long guardianId = onlyGuardian.guardians().getFirst().id();

		assertFails(() -> studentService.removePhone(onlyPhone.id()), MemberErrorCode.LAST_CONTACT);
		assertFails(() -> studentService.removeGuardian(onlyGuardian.id(), guardianId), MemberErrorCode.LAST_CONTACT);

		assertThat(studentService.get(onlyPhone.id()).phone()).isEqualTo(studentPhone);
		assertThat(studentService.get(onlyGuardian.id()).guardians()).hasSize(1);
	}

	@Test
	void 학부모를_추가한다() {
		PhoneNumber existingPhone = randomPhone();
		PhoneNumber newPhone = randomPhone();
		long otherFamilyGuardian = studentService.register(registration(uniqueName(), mother(existingPhone)))
				.guardians()
				.getFirst()
				.id();
		StudentView student = studentService.register(registration(uniqueName(), father(randomPhone())));

		StudentView withNew = studentService.addGuardian(student.id(), mother(newPhone));
		StudentView withExisting = studentService.addGuardian(student.id(),
				new GuardianInput("이름 다름", GuardianRelation.GRANDPARENT, existingPhone));

		assertThat(withNew.guardians()).hasSize(2);
		assertThat(withNew.guardians().getLast().linkedToExisting()).isFalse();
		assertThat(withNew.guardians().getLast().phone()).isEqualTo(newPhone);
		// 어머니가 붙었으니 대표가 바뀐다.
		assertThat(withNew.primaryContact().relation()).isEqualTo(GuardianRelation.MOTHER);
		assertThat(withExisting.guardians()).hasSize(3);
		assertThat(withExisting.guardians().getLast()).satisfies(added -> {
			assertThat(added.id()).isEqualTo(otherFamilyGuardian);
			assertThat(added.linkedToExisting()).isTrue();
			assertThat(added.name()).isEqualTo("김어머니");
			assertThat(added.relation()).isEqualTo(GuardianRelation.GRANDPARENT);
		});
	}

	@Test
	void 이미_붙어_있는_학부모는_다시_붙일_수_없다() {
		PhoneNumber phone = randomPhone();
		StudentView student = studentService.register(registration(uniqueName(), mother(phone)));

		assertFails(() -> studentService.addGuardian(student.id(), father(phone)),
				MemberErrorCode.GUARDIAN_ALREADY_LINKED);

		assertThat(studentService.get(student.id()).guardians()).singleElement()
				.satisfies(each -> assertThat(each.relation()).isEqualTo(GuardianRelation.MOTHER));
	}

	@Test
	void 관계를_고친다() {
		StudentView student = studentService.register(registration(uniqueName(), mother(randomPhone())));
		long guardianId = student.guardians().getFirst().id();

		StudentView changed = studentService.changeRelation(student.id(), guardianId, GuardianRelation.GRANDPARENT);

		assertThat(changed.guardians().getFirst().relation()).isEqualTo(GuardianRelation.GRANDPARENT);
		assertThat(studentService.get(student.id()).guardians().getFirst().relation())
				.isEqualTo(GuardianRelation.GRANDPARENT);
		assertFails(() -> studentService.changeRelation(student.id(), 999_999_999L, GuardianRelation.FATHER),
				MemberErrorCode.GUARDIAN_NOT_FOUND);
	}

	// 형제가 그 학부모에 붙어 있을 수 있다. 연결만 끊고 학부모는 남긴다.
	@Test
	void 연결을_끊어도_학부모와_형제의_연결은_남는다() {
		PhoneNumber motherPhone = randomPhone();
		StudentView first = studentService.register(registration(uniqueName(), mother(motherPhone), father(randomPhone())));
		StudentView second = studentService.register(registration(uniqueName(), mother(motherPhone)));
		long motherId = first.guardians().getFirst().id();

		studentService.removeGuardian(first.id(), motherId);

		assertThat(studentService.get(first.id()).guardians()).extracting(GuardianView::relation)
				.containsExactly(GuardianRelation.FATHER);
		assertThat(studentService.get(first.id()).siblings()).isEmpty();
		assertThat(studentService.get(second.id()).guardians()).extracting(GuardianView::id).containsExactly(motherId);
		assertThat(guardians.findById(motherId)).isPresent();
	}

	@Test
	void 붙어_있지_않은_학부모는_끊을_수_없다() {
		StudentView student = studentService.register(registration(uniqueName(), mother(randomPhone())));
		long stranger = studentService.register(registration(uniqueName(), mother(randomPhone())))
				.guardians()
				.getFirst()
				.id();

		assertFails(() -> studentService.removeGuardian(student.id(), stranger), MemberErrorCode.GUARDIAN_NOT_FOUND);
	}

	@Test
	void 학부모의_이름과_번호를_고친다() {
		String oldLast4 = randomLast4();
		String newLast4 = String.format("%04d", (Integer.parseInt(oldLast4) + 1) % 10_000);
		String name = uniqueName();
		StudentView student = studentService.register(registration(name, mother(randomPhoneEndingWith(oldLast4))));
		long guardianId = student.guardians().getFirst().id();
		PhoneNumber changed = randomPhoneEndingWith(newLast4);

		GuardianProfile renamed = guardianService.update(guardianId, " 박어머니 ", null);
		GuardianProfile moved = guardianService.update(guardianId, "박어머니", changed);

		assertThat(renamed.name()).isEqualTo("박어머니");
		assertThat(renamed.phone().last4()).isEqualTo(oldLast4);
		assertThat(moved.phone()).isEqualTo(changed);
		assertThat(studentService.get(student.id()).guardians().getFirst().phone()).isEqualTo(changed);
		// 네 값이 통째로 바뀐다. 옛 뒷자리로는 더 찾아지지 않는다.
		assertThat(studentService.search(null, name, newLast4, 0, 20)).hasSize(1);
		assertThat(studentService.search(null, name, oldLast4, 0, 20)).isEmpty();
	}

	@Test
	void 다른_학부모가_쓰는_번호로는_바꿀_수_없다() {
		PhoneNumber mine = randomPhone();
		PhoneNumber others = randomPhone();
		long guardianId = studentService.register(registration(uniqueName(), mother(mine))).guardians().getFirst().id();
		studentService.register(registration(uniqueName(), mother(others)));

		assertFails(() -> guardianService.update(guardianId, "김어머니", others),
				MemberErrorCode.GUARDIAN_PHONE_DUPLICATED);
		// 자기 번호를 그대로 보내는 것은 된다.
		assertThat(guardianService.update(guardianId, "김어머니", mine).phone()).isEqualTo(mine);
		assertFails(() -> guardianService.update(999_999_999L, "김어머니", null), MemberErrorCode.GUARDIAN_NOT_FOUND);
	}

	private AcademyClass newClass(long campusId) {
		return classService.create(campusId, "반-" + uniqueName(), Money.of(280_000), Money.ZERO);
	}

	// 나머지 값은 그대로 두고 지점, 반, 번호만 바꾼 수정.
	private static StudentChange change(StudentView current, long campusId, Long classId, PhoneNumber phone) {
		return new StudentChange(current.version(), campusId, classId, current.name(), current.school(), current.grade(),
				current.status(), current.registeredAt(), phone);
	}

	// 위와 같고 학교도 바꾼다(값이 실제로 바뀌는 저장이 필요할 때).
	private static StudentChange change(StudentView current, long campusId, Long classId, PhoneNumber phone,
			String school) {
		return new StudentChange(current.version(), campusId, classId, current.name(), school, current.grade(),
				current.status(), current.registeredAt(), phone);
	}

}
