package com.jey.core.member;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import com.jey.TestcontainersConfiguration;
import com.jey.core.campus.ClassService;
import com.jey.core.campus.domain.AcademyClass;
import com.jey.core.member.StudentView.GuardianView;
import com.jey.core.member.domain.Grade;
import com.jey.core.member.domain.GuardianRelation;
import com.jey.core.member.domain.GuardianRepository;
import com.jey.core.member.domain.StudentGuardianRepository;
import com.jey.core.member.domain.StudentRepository;
import com.jey.core.member.domain.StudentStatus;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.ErrorCode;
import com.jey.core.shared.Money;
import com.jey.core.shared.PhoneNumber;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static com.jey.core.member.MemberFixtures.DONGTAN;
import static com.jey.core.member.MemberFixtures.NEUNGDONG;
import static com.jey.core.member.MemberFixtures.father;
import static com.jey.core.member.MemberFixtures.mother;
import static com.jey.core.member.MemberFixtures.randomPhone;
import static com.jey.core.member.MemberFixtures.registration;
import static com.jey.core.member.MemberFixtures.registrationWithPhone;
import static com.jey.core.member.MemberFixtures.uniqueName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class StudentRegistrationTest {

	@Autowired
	StudentService studentService;

	@Autowired
	ClassService classService;

	@Autowired
	StudentRepository students;

	@Autowired
	GuardianRepository guardians;

	@Autowired
	StudentGuardianRepository links;

	@Autowired
	Clock clock;

	@Test
	void 학부모와_함께_등록하면_학생과_학부모와_연결이_생긴다() {
		String name = uniqueName();
		PhoneNumber motherPhone = randomPhone();
		PhoneNumber fatherPhone = randomPhone();

		StudentView created = studentService.register(registration(name, father(fatherPhone), mother(motherPhone)));

		assertThat(created.name()).isEqualTo(name);
		assertThat(created.campusId()).isEqualTo(DONGTAN);
		assertThat(created.classId()).isNull();
		assertThat(created.school()).isEqualTo("동탄고");
		assertThat(created.grade()).isEqualTo(Grade.HIGH_1);
		assertThat(created.status()).isEqualTo(StudentStatus.ENROLLED);
		assertThat(created.phone()).isNull();
		assertThat(created.guardians()).extracting(GuardianView::relation)
				.containsExactly(GuardianRelation.FATHER, GuardianRelation.MOTHER);
		assertThat(created.guardians()).extracting(GuardianView::phone).containsExactly(fatherPhone, motherPhone);
		assertThat(created.guardians()).extracting(GuardianView::linkedToExisting).containsOnly(false);
		assertThat(links.countByStudentId(created.id())).isEqualTo(2);
		assertThat(students.findById(created.id())).isPresent();
	}

	@Test
	void 등록일을_주지_않으면_오늘이다() {
		StudentView today = studentService.register(registration(uniqueName(), mother(randomPhone())));
		StudentView given = studentService.register(new StudentRegistration(DONGTAN, null, uniqueName(), null,
				Grade.HIGH_1, LocalDate.of(2026, 3, 2), null, List.of(mother(randomPhone()))));

		assertThat(today.registeredAt()).isEqualTo(LocalDate.now(clock));
		assertThat(given.registeredAt()).isEqualTo(LocalDate.of(2026, 3, 2));
	}

	// 형제를 등록할 때마다 학부모가 새로 생기면 형제 관계를 알 수 없다.
	@Test
	void 같은_번호의_학부모가_있으면_새로_만들지_않고_붙인다() {
		PhoneNumber phone = randomPhone();
		StudentView first = studentService.register(registration(uniqueName(), mother(phone)));

		StudentView second = studentService.register(registration(uniqueName(), mother(phone)));

		assertThat(second.guardians()).singleElement()
				.satisfies(guardian -> {
					assertThat(guardian.id()).isEqualTo(first.guardians().getFirst().id());
					assertThat(guardian.linkedToExisting()).isTrue();
				});
		assertThat(first.guardians().getFirst().linkedToExisting()).isFalse();
	}

	@Test
	void 적는_방식이_달라도_같은_번호면_같은_학부모다() {
		PhoneNumber phone = randomPhone();
		String digits = phone.value();
		StudentView first = studentService.register(registration(uniqueName(), mother(phone)));

		StudentView second = studentService.register(registration(uniqueName(), mother(PhoneNumber
				.of("+82 " + digits.substring(1, 3) + " " + digits.substring(3, 7) + " " + digits.substring(7)))));

		assertThat(second.guardians().getFirst().id()).isEqualTo(first.guardians().getFirst().id());
	}

	// 형제를 등록하면서 학부모 이름을 조금 다르게 적어도 같은 학부모에 붙는다. 저장된 이름은 그대로 둔다.
	@Test
	void 같은_번호인데_이름이_달라도_기존_학부모에_붙이고_이름은_바꾸지_않는다() {
		PhoneNumber phone = randomPhone();
		studentService.register(registration(uniqueName(), new GuardianInput("김영희", GuardianRelation.MOTHER, phone)));

		StudentView second = studentService
				.register(registration(uniqueName(), new GuardianInput("김영히", GuardianRelation.MOTHER, phone)));

		assertThat(second.guardians()).singleElement().satisfies(guardian -> {
			assertThat(guardian.name()).isEqualTo("김영희");
			assertThat(guardian.linkedToExisting()).isTrue();
		});
	}

	// 같은 사람이 한 학생에게는 어머니, 다른 학생에게는 다른 관계로 붙을 수 있다.
	@Test
	void 관계는_학생마다_따로_갖는다() {
		PhoneNumber phone = randomPhone();
		StudentView first = studentService.register(registration(uniqueName(), mother(phone)));

		StudentView second = studentService
				.register(registration(uniqueName(), new GuardianInput("김어머니", GuardianRelation.OTHER, phone)));

		assertThat(second.guardians().getFirst().relation()).isEqualTo(GuardianRelation.OTHER);
		assertThat(studentService.get(first.id()).guardians().getFirst().relation())
				.isEqualTo(GuardianRelation.MOTHER);
	}

	@Test
	void 학부모_없이_학생_번호만으로_등록된다() {
		PhoneNumber phone = randomPhone();
		String name = uniqueName();

		StudentView created = studentService.register(registrationWithPhone(name, phone));

		assertThat(created.phone()).isEqualTo(phone);
		assertThat(created.guardians()).isEmpty();
		assertThat(created.primaryContact().name()).isEqualTo(name);
		assertThat(created.primaryContact().relation()).isNull();
		assertThat(created.primaryContact().phone()).isEqualTo(phone);
	}

	@Test
	void 학부모도_학생_번호도_없으면_등록되지_않는다() {
		assertFails(() -> studentService.register(registration(uniqueName())), MemberErrorCode.CONTACT_REQUIRED);
		assertFails(() -> studentService.register(new StudentRegistration(DONGTAN, null, uniqueName(), null,
				Grade.HIGH_1, null, null, null)), MemberErrorCode.CONTACT_REQUIRED);
	}

	@Test
	void 한_요청에_같은_번호의_학부모를_두_번_적을_수_없다() {
		PhoneNumber phone = randomPhone();
		String name = uniqueName();

		assertFails(() -> studentService.register(registration(name, mother(phone), father(phone))),
				MemberErrorCode.GUARDIAN_PHONE_REPEATED);

		// 학부모를 만들기 전에 걸러야 한다.
		assertThat(guardians.findByPhoneIndex(indexOf(phone))).isEmpty();
	}

	// 학부모 번호를 같이 쓰는 학생이 있다.
	@Test
	void 학부모_번호와_학생_번호는_같아도_된다() {
		PhoneNumber phone = randomPhone();

		StudentView created = studentService.register(registrationWithPhone(uniqueName(), phone, mother(phone)));

		assertThat(created.phone()).isEqualTo(phone);
		assertThat(created.guardians().getFirst().phone()).isEqualTo(phone);
	}

	@Test
	void 없는_지점에는_등록할_수_없다() {
		PhoneNumber phone = randomPhone();

		assertFails(() -> studentService.register(new StudentRegistration(999_999, null, uniqueName(), null,
				Grade.HIGH_1, null, null, List.of(mother(phone)))), MemberErrorCode.INVALID_CAMPUS);

		assertThat(guardians.findByPhoneIndex(indexOf(phone))).isEmpty();
	}

	@Test
	void 반은_학생의_지점에서_운영_중인_반이어야_한다() {
		AcademyClass dongtan = classService.create(DONGTAN, "반-" + uniqueName(), Money.of(280_000), Money.ZERO);
		AcademyClass neungdong = classService.create(NEUNGDONG, "반-" + uniqueName(), Money.of(280_000), Money.ZERO);
		AcademyClass closed = classService.create(DONGTAN, "반-" + uniqueName(), Money.of(280_000), Money.ZERO);
		classService.update(closed.getId(), closed.getName(), Money.of(280_000), Money.ZERO, false);

		assertThat(studentService.register(withClass(dongtan.getId())).classId()).isEqualTo(dongtan.getId());
		assertFails(() -> studentService.register(withClass(neungdong.getId())), MemberErrorCode.INVALID_CLASS);
		assertFails(() -> studentService.register(withClass(closed.getId())), MemberErrorCode.INVALID_CLASS);
		assertFails(() -> studentService.register(withClass(999_999_999L)), MemberErrorCode.INVALID_CLASS);
	}

	// 키가 다른 같은 등록 요청(버튼을 두 번 누름)은 Idempotency-Key가 막지 못한다. 그때도 학생이 둘 생기지 않게 한다.
	@Test
	void 같은_학부모에게_같은_이름의_학생을_두_번_등록할_수_없다() {
		PhoneNumber motherPhone = randomPhone();
		PhoneNumber fatherPhone = randomPhone();
		String name = uniqueName();
		studentService.register(registration(name, mother(motherPhone)));
		long before = students.count();

		assertFails(() -> studentService.register(registration(name, mother(motherPhone))),
				MemberErrorCode.STUDENT_DUPLICATED);
		assertFails(() -> studentService.register(registration(" " + name + " ", mother(motherPhone))),
				MemberErrorCode.STUDENT_DUPLICATED);
		// 학부모 둘 중 한 명만 같아도 같은 학생이다.
		assertFails(() -> studentService.register(registration(name, father(fatherPhone), mother(motherPhone))),
				MemberErrorCode.STUDENT_DUPLICATED);

		assertThat(students.count()).isEqualTo(before);
		// 실패한 요청이 새 학부모를 남기지 않는다.
		assertThat(guardians.findByPhoneIndex(indexOf(fatherPhone))).isEmpty();
	}

	// 쌍둥이와 형제는 이름이 다르다. 다른 집의 동명이인도 막지 않는다.
	@Test
	void 이름이_다르거나_학부모가_다르면_등록된다() {
		PhoneNumber phone = randomPhone();
		String name = uniqueName();
		studentService.register(registration(name, mother(phone)));

		studentService.register(registration(uniqueName(), mother(phone)));
		studentService.register(registration(name, mother(randomPhone())));
	}

	@Test
	void 학부모_없이_등록한_학생은_같은_이름과_같은_번호면_중복이다() {
		PhoneNumber phone = randomPhone();
		String name = uniqueName();
		studentService.register(registrationWithPhone(name, phone));

		assertFails(() -> studentService.register(registrationWithPhone(name, phone)),
				MemberErrorCode.STUDENT_DUPLICATED);
		studentService.register(registrationWithPhone(uniqueName(), phone));
	}

	private StudentRegistration withClass(long classId) {
		return new StudentRegistration(DONGTAN, classId, uniqueName(), null, Grade.HIGH_1, null, null,
				List.of(mother(randomPhone())));
	}

	private String indexOf(PhoneNumber phone) {
		return com.jey.core.shared.TestPhones.protector().fullIndex(phone);
	}

	// 화면의 등록은 요청 검증에서 먼저 걸린다. 요청을 거치지 않고 부르는 경로(일괄 등록 등)도 같은 상한을 넘지 못해야 한다.
	@Test
	void 학부모를_다섯_명_적은_등록은_거부되고_학생도_학부모도_생기지_않는다() {
		String name = uniqueName();
		long guardiansBefore = guardians.count();

		assertFails(() -> studentService.register(registration(name, mother(randomPhone()), father(randomPhone()),
				new GuardianInput("김할머니", GuardianRelation.GRANDPARENT, randomPhone()),
				new GuardianInput("김고모", GuardianRelation.OTHER, randomPhone()),
				new GuardianInput("김이모", GuardianRelation.OTHER, randomPhone()))), MemberErrorCode.TOO_MANY_GUARDIANS);

		assertThat(studentService.search(null, name, null, 0, 20).getContent()).isEmpty();
		assertThat(guardians.count()).isEqualTo(guardiansBefore);
	}

	@Test
	void 학부모를_네_명_적은_등록은_된다() {
		StudentView created = studentService.register(registration(uniqueName(), mother(randomPhone()),
				father(randomPhone()), new GuardianInput("김할머니", GuardianRelation.GRANDPARENT, randomPhone()),
				new GuardianInput("김고모", GuardianRelation.OTHER, randomPhone())));

		assertThat(created.guardians()).hasSize(4);
	}

	static void assertFails(ThrowingCallable call, ErrorCode expected) {
		assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
				ex -> assertThat(ex.getErrorCode()).isEqualTo(expected));
	}

}
