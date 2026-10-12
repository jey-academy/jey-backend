package com.jey.core.member;

import java.util.List;

import com.jey.TestcontainersConfiguration;
import com.jey.core.member.StudentView.ContactView;
import com.jey.core.member.StudentView.GuardianView;
import com.jey.core.member.StudentView.SiblingView;
import com.jey.core.member.api.MemberDirectory;
import com.jey.core.member.domain.Grade;
import com.jey.core.member.domain.GuardianRelation;
import com.jey.core.member.domain.GuardianRepository;
import com.jey.core.member.domain.StudentStatus;
import com.jey.core.shared.PhoneNumber;
import com.jey.core.shared.TestPhones;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import static com.jey.core.member.MemberFixtures.DONGTAN;
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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

// 연락처와 대표 연락처, 형제에 관한 규칙 가운데 다른 테스트가 한쪽 방향만 본 것들.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class StudentContactTest {

	@Autowired
	StudentService studentService;

	@Autowired
	MemberDirectory directory;

	@Autowired
	GuardianRepository guardians;

	@Autowired
	TransactionTemplate transaction;

	// 잘못 이은 학부모는 연결을 끊어 고친다. 본인 번호가 있으면 학부모가 한 명뿐이어도 끊을 수 있어야 한다.
	@Test
	void 본인_번호가_있으면_하나뿐인_학부모도_끊을_수_있다() {
		PhoneNumber studentPhone = randomPhone();
		String name = uniqueName();
		StudentView student = studentService.register(registrationWithPhone(name, studentPhone));
		long guardianId = studentService.addGuardian(student.id(), mother(randomPhone())).guardians().getFirst().id();
		assertThat(studentService.get(student.id()).primaryContact().relation()).isEqualTo(GuardianRelation.MOTHER);

		studentService.removeGuardian(student.id(), guardianId);

		StudentView after = studentService.get(student.id());
		assertThat(after.guardians()).isEmpty();
		assertThat(after.primaryContact()).isEqualTo(ContactView.ofStudent(name, studentPhone));
		assertThat(after.primaryContact().studentOwn()).isTrue();
	}

	@Test
	void 학부모를_붙인_뒤에는_본인_번호를_지울_수_있고_그러면_그_학부모가_마지막_연락처다() {
		StudentView student = studentService.register(registrationWithPhone(uniqueName(), randomPhone()));
		long guardianId = studentService.addGuardian(student.id(), father(randomPhone())).guardians().getFirst().id();

		studentService.removePhone(student.id());

		assertThat(studentService.get(student.id()).primaryContact().studentOwn()).isFalse();
		assertFails(() -> studentService.removeGuardian(student.id(), guardianId), MemberErrorCode.LAST_CONTACT);
	}

	@Test
	void 번호_없는_학생에게_수정으로_번호를_넣으면_그_뒷자리로_찾아진다() {
		String name = uniqueName();
		String last4 = randomLast4();
		String motherLast4 = String.format("%04d", (Integer.parseInt(last4) + 1) % 10_000);
		StudentView student = studentService.register(registration(name, mother(randomPhoneEndingWith(motherLast4))));
		PhoneNumber phone = randomPhoneEndingWith(last4);
		assertThat(studentService.search(null, name, last4, 0, 20)).isEmpty();

		StudentView updated = studentService.update(student.id(), new StudentChange(DONGTAN, null, name, null,
				Grade.HIGH_1, StudentStatus.ENROLLED, student.registeredAt(), phone));

		assertThat(updated.phone()).isEqualTo(phone);
		assertThat(studentService.search(null, name, last4, 0, 20)).hasSize(1);
	}

	// 목록은 학생과 대표 연락처를 따로 읽어 짝짓는다. 짝이 어긋나면 알림이 남의 집으로 간다.
	@Test
	void 목록의_대표_연락처가_학생마다_제_것이다() {
		String prefix = uniqueName();
		PhoneNumber sharedMother = randomPhone();
		PhoneNumber otherFather = randomPhone();
		PhoneNumber ownPhone = randomPhone();
		studentService.register(registration(prefix + "-1", father(randomPhone()),
				new GuardianInput("형제 어머니", GuardianRelation.MOTHER, sharedMother)));
		studentService.register(registration(prefix + "-2",
				new GuardianInput("형제 어머니", GuardianRelation.MOTHER, sharedMother)));
		studentService.register(registration(prefix + "-3",
				new GuardianInput("다른 집 아버지", GuardianRelation.FATHER, otherFather)));
		studentService.register(registrationWithPhone(prefix + "-4", ownPhone));

		Page<StudentSummaryView> found = studentService.search(null, prefix, null, 0, 20);

		assertThat(found.getContent()).extracting(StudentSummaryView::primaryContact)
				.containsExactly(ContactView.ofGuardian("형제 어머니", GuardianRelation.MOTHER, sharedMother),
						ContactView.ofGuardian("형제 어머니", GuardianRelation.MOTHER, sharedMother),
						ContactView.ofGuardian("다른 집 아버지", GuardianRelation.FATHER, otherFather),
						ContactView.ofStudent(prefix + "-4", ownPhone));
	}

	@Test
	void 페이지가_범위를_넘으면_빈_목록이고_전체_건수는_그대로다() {
		String prefix = uniqueName();
		studentService.register(registration(prefix + "-1", mother(randomPhone())));
		studentService.register(registration(prefix + "-2", mother(randomPhone())));

		Page<StudentSummaryView> beyond = studentService.search(null, prefix, null, 9, 20);

		assertThat(beyond.getContent()).isEmpty();
		assertThat(beyond.getTotalElements()).isEqualTo(2);
	}

	// 형제 할인을 고를 때 참고하는 목록이다. 퇴원한 형제를 빼면 목록이 조용히 달라진다.
	@Test
	void 퇴원한_형제도_형제로_나온다() {
		PhoneNumber phone = randomPhone();
		StudentView staying = studentService.register(registration(uniqueName(), mother(phone)));
		StudentView leaving = studentService.register(registration(uniqueName(), mother(phone)));
		studentService.update(leaving.id(), new StudentChange(DONGTAN, null, leaving.name(), null, Grade.HIGH_1,
				StudentStatus.WITHDRAWN, leaving.registeredAt(), null));

		assertThat(studentService.get(staying.id()).siblings())
				.containsExactly(new SiblingView(leaving.id(), leaving.name(), StudentStatus.WITHDRAWN));
		assertThat(directory.siblingIdsOf(staying.id())).containsExactly(leaving.id());
	}

	@Test
	void 학부모를_추가해_기존_학부모에_이으면_형제가_된다() {
		PhoneNumber phone = randomPhone();
		StudentView first = studentService.register(registration(uniqueName(), mother(phone)));
		StudentView second = studentService.register(registration(uniqueName(), father(randomPhone())));

		StudentView linked = studentService.addGuardian(second.id(), mother(phone));

		assertThat(linked.siblings()).extracting(SiblingView::id).containsExactly(first.id());
		assertThat(studentService.get(first.id()).siblings()).extracting(SiblingView::id).containsExactly(second.id());
	}

	@Test
	void 한_번의_등록에_기존_학부모와_새_학부모가_섞여도_각각_표시된다() {
		PhoneNumber existing = randomPhone();
		studentService.register(registration(uniqueName(), mother(existing)));

		StudentView created = studentService.register(registration(uniqueName(), mother(existing), father(randomPhone())));

		assertThat(created.guardians()).extracting(GuardianView::linkedToExisting).containsExactly(true, false);
	}

	// 학부모 번호를 바꾸면 그 학부모에 연결된 형제 모두에게 반영된다.
	@Test
	void 학부모_번호를_바꾸면_형제_모두의_연락처가_바뀐다(@Autowired GuardianService guardianService) {
		PhoneNumber original = randomPhone();
		PhoneNumber changed = randomPhone();
		StudentView first = studentService.register(registration(uniqueName(), mother(original)));
		StudentView second = studentService.register(registration(uniqueName(), mother(original)));

		guardianService.update(first.guardians().getFirst().id(), "김어머니", changed);

		assertThat(List.of(studentService.get(first.id()), studentService.get(second.id())))
				.allSatisfy(each -> assertThat(each.primaryContact().phone()).isEqualTo(changed));
	}

	@Test
	void 번호_중복으로_실패한_학부모_수정은_이름도_바꾸지_않는다(@Autowired GuardianService guardianService) {
		PhoneNumber others = randomPhone();
		studentService.register(registration(uniqueName(), mother(others)));
		StudentView student = studentService.register(registration(uniqueName(), mother(randomPhone())));
		long guardianId = student.guardians().getFirst().id();

		assertFails(() -> guardianService.update(guardianId, "바뀐 이름", others),
				MemberErrorCode.GUARDIAN_PHONE_DUPLICATED);

		assertThat(studentService.get(student.id()).guardians().getFirst().name()).isEqualTo("김어머니");
	}

	// 학부모는 따로 커밋된다. 바깥에 트랜잭션이 열려 있으면 방금 만든 학부모가 보이지 않아 엉뚱한 곳에서 실패한다.
	// 그 전에 무엇이 잘못됐는지 알려 주고 멈춘다.
	// 등록은 한 번에 네 명까지다. 나중에 한 명씩 붙이는 경로에 같은 상한이 없으면 그 상한은 뜻이 없다.
	@Test
	void 학부모가_네_명인_학생에게는_더_붙일_수_없고_학부모도_새로_생기지_않는다() {
		long id = studentService
			.register(registration(uniqueName(), mother(randomPhone()), father(randomPhone()),
					new GuardianInput("김할머니", GuardianRelation.GRANDPARENT, randomPhone())))
			.id();
		studentService.addGuardian(id, new GuardianInput("김고모", GuardianRelation.OTHER, randomPhone()));
		long guardiansBefore = guardians.count();

		assertFails(() -> studentService.addGuardian(id, new GuardianInput("김이모", GuardianRelation.OTHER, randomPhone())),
				MemberErrorCode.TOO_MANY_GUARDIANS);

		assertThat(studentService.get(id).guardians()).hasSize(4);
		// 거부될 요청 때문에 어디에도 연결되지 않은 학부모 행이 남지 않는다.
		assertThat(guardians.count()).isEqualTo(guardiansBefore);
	}

	// 이미 붙어 있는 학부모를 다시 붙이려 해도, 꽉 찬 학생이면 "꽉 찼다"로 답한다. 그 번호가 이미 있다는 것을 드러내지 않는다.
	@Test
	void 꽉_찬_학생에게_이미_붙은_학부모를_다시_붙이려_해도_상한으로_거부된다() {
		PhoneNumber motherPhone = randomPhone();
		long id = studentService
			.register(registration(uniqueName(), mother(motherPhone), father(randomPhone()),
					new GuardianInput("김할머니", GuardianRelation.GRANDPARENT, randomPhone()),
					new GuardianInput("김고모", GuardianRelation.OTHER, randomPhone())))
			.id();

		assertFails(() -> studentService.addGuardian(id, mother(motherPhone)), MemberErrorCode.TOO_MANY_GUARDIANS);
	}

	@Test
	void 등록과_학부모_추가는_트랜잭션_안에서_부를_수_없다() {
		PhoneNumber phone = randomPhone();
		StudentView student = studentService.register(registration(uniqueName(), mother(randomPhone())));

		assertThatIllegalStateException()
				.isThrownBy(() -> transaction
						.execute(status -> studentService.register(registration(uniqueName(), mother(phone)))))
				.withMessageContaining("트랜잭션 밖");
		assertThatIllegalStateException()
				.isThrownBy(() -> transaction.execute(status -> studentService.addGuardian(student.id(), father(phone))))
				.withMessageContaining("트랜잭션 밖");

		assertThat(guardians.findByPhoneIndex(TestPhones.protector().fullIndex(phone))).isEmpty();
	}

	// 서비스를 직접 부르는 쪽(다른 모듈, 배치)은 요청 검증을 거치지 않는다.
	// 틀린 값이 학부모를 커밋한 뒤에 걸리지 않도록 입력을 만들 때 거른다.
	@Test
	void 학부모_입력은_이름과_관계와_번호가_모두_있어야_만들어진다() {
		PhoneNumber phone = randomPhone();

		assertThatIllegalArgumentException().isThrownBy(() -> new GuardianInput(" ", GuardianRelation.MOTHER, phone));
		assertThatIllegalArgumentException().isThrownBy(() -> new GuardianInput(null, GuardianRelation.MOTHER, phone));
		assertThatIllegalArgumentException().isThrownBy(() -> new GuardianInput("김어머니", null, phone));
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new GuardianInput("김어머니", GuardianRelation.MOTHER, null))
				.withMessageNotContaining("010");
		assertThatIllegalArgumentException().isThrownBy(() -> ContactView.ofGuardian("김어머니", null, phone));
	}

	@Test
	void 없는_학생의_관계는_고칠_수_없다() {
		assertFails(() -> studentService.changeRelation(999_999_999L, 1, GuardianRelation.FATHER),
				MemberErrorCode.STUDENT_NOT_FOUND);
	}

}
