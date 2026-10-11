package com.jey.core.member;

import java.time.Duration;
import java.util.List;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.audit.AuditLogService;
import com.jey.core.audit.domain.AuditRecord;
import com.jey.core.auth.api.UserRole;
import com.jey.core.member.api.StudentRegistered;
import com.jey.core.member.domain.Grade;
import com.jey.core.member.domain.GuardianRelation;
import com.jey.core.member.domain.StudentStatus;
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
import static com.jey.core.member.MemberFixtures.randomPhone;
import static com.jey.core.member.MemberFixtures.registration;
import static com.jey.core.member.MemberFixtures.registrationWithPhone;
import static com.jey.core.member.MemberFixtures.uniqueName;
import static com.jey.core.member.StudentRegistrationTest.assertFails;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// 학생·학부모에 대해 한 일이 감사 로그에 남는지 본다. 기록은 학생이나 학부모의 ID로 찾는다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class MemberAuditTest {

	private static final long STAFF = 4242;

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	@Autowired
	StudentService studentService;

	@Autowired
	GuardianService guardianService;

	@Autowired
	AuditLogService auditLogService;

	// 등록은 도메인 이벤트로 기록된다. 리스너는 등록이 커밋된 뒤 다른 스레드에서 돌기 때문에 기다려서 본다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = STAFF)
	void 학생을_등록하면_등록_이벤트와_학부모_연결이_기록된다() {
		PhoneNumber existingPhone = randomPhone();
		long existingGuardian = studentService.register(registration(uniqueName(), mother(existingPhone)))
				.guardians()
				.getFirst()
				.id();

		StudentView created = studentService.register(new StudentRegistration(NEUNGDONG, null, uniqueName(), null,
				Grade.HIGH_1, null, null, List.of(mother(existingPhone), father(randomPhone()))));

		await().atMost(TIMEOUT).untilAsserted(() -> assertThat(registeredEventOf(created.id())).hasSize(1));
		AuditRecord registered = registeredEventOf(created.id()).getFirst();
		assertThat(registered.getActorId()).isEqualTo(STAFF);
		assertThat(registered.getCampusId()).isEqualTo(NEUNGDONG);
		assertThat(registered.getEventId()).isNotNull();
		assertThat(registered.getDetailJson()).isEqualTo("{\"studentId\":" + created.id() + "}");
		long newGuardian = created.guardians().getLast().id();
		assertThat(ofStudent(created.id(), MemberAudit.GUARDIAN_LINKED)).extracting(AuditRecord::getDetailJson)
				.containsExactlyInAnyOrder("{\"guardianId\":" + existingGuardian + ",\"linkedToExisting\":true}",
						"{\"guardianId\":" + newGuardian + ",\"linkedToExisting\":false}");
		assertThat(ofStudent(created.id(), MemberAudit.GUARDIAN_LINKED))
				.allSatisfy(each -> assertThat(each.getActorId()).isEqualTo(STAFF));
	}

	// 등록이 실패하면 이벤트도 연결 기록도 남지 않는다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = STAFF)
	void 실패한_등록은_기록되지_않는다() {
		PhoneNumber phone = randomPhone();
		String name = uniqueName();
		StudentView existing = studentService.register(registration(name, mother(phone)));
		long before = auditLogService.search(null, StudentRegistered.TYPE, null, null, null, null, 0, 1)
				.getTotalElements();

		assertFails(() -> studentService.register(registration(name, mother(phone))),
				MemberErrorCode.STUDENT_DUPLICATED);

		await().atMost(TIMEOUT).untilAsserted(() -> assertThat(registeredEventOf(existing.id())).hasSize(1));
		assertThat(auditLogService.search(null, StudentRegistered.TYPE, null, null, null, null, 0, 1)
				.getTotalElements()).isLessThanOrEqualTo(before + 1);
		assertThat(ofStudent(existing.id(), MemberAudit.GUARDIAN_LINKED)).hasSize(1);
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = STAFF)
	void 학생_정보_수정과_번호_삭제가_기록된다() {
		String name = uniqueName();
		StudentView student = studentService.register(registration(name, mother(randomPhone())));

		studentService.update(student.id(), change(student, null));
		studentService.update(student.id(), change(student, randomPhone()));
		studentService.removePhone(student.id());
		// 원래 번호가 없으면 아무 일도 하지 않으므로 기록도 없다.
		studentService.removePhone(student.id());

		assertThat(ofStudent(student.id(), MemberAudit.STUDENT_UPDATED)).extracting(AuditRecord::getDetailJson)
				.containsExactlyInAnyOrder("{\"phoneChanged\":false}", "{\"phoneChanged\":true}");
		assertThat(ofStudent(student.id(), MemberAudit.STUDENT_PHONE_REMOVED)).singleElement().satisfies(each -> {
			assertThat(each.getActorId()).isEqualTo(STAFF);
			assertThat(each.getTargetType()).isEqualTo("student");
			assertThat(each.getDetailJson()).isNull();
		});
	}

	// "수정했다"는 기록은 수정이 실제로 됐을 때만 남아야 한다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = STAFF)
	void 거부된_수정과_삭제는_했다는_기록을_남기지_않는다() {
		StudentView student = studentService.register(registration(uniqueName(), mother(randomPhone())));
		long guardianId = student.guardians().getFirst().id();

		assertFails(() -> studentService.update(student.id(), new StudentChange(DONGTAN, 999_999_999L, student.name(),
				null, Grade.HIGH_1, StudentStatus.ENROLLED, student.registeredAt(), null)),
				MemberErrorCode.INVALID_CLASS);
		assertFails(() -> studentService.removeGuardian(student.id(), guardianId), MemberErrorCode.LAST_CONTACT);

		assertThat(ofStudent(student.id(), MemberAudit.STUDENT_UPDATED)).isEmpty();
		assertThat(ofStudent(student.id(), MemberAudit.GUARDIAN_UNLINKED)).isEmpty();
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = STAFF)
	void 학부모_추가와_관계_수정과_연결_끊기가_기록된다() {
		StudentView student = studentService.register(registrationWithPhone(uniqueName(), randomPhone()));

		long guardianId = studentService.addGuardian(student.id(), father(randomPhone())).guardians().getFirst().id();
		studentService.changeRelation(student.id(), guardianId, GuardianRelation.GRANDPARENT);
		studentService.removeGuardian(student.id(), guardianId);

		assertThat(ofStudent(student.id(), MemberAudit.GUARDIAN_LINKED)).singleElement()
				.satisfies(each -> assertThat(each.getDetailJson())
						.isEqualTo("{\"guardianId\":" + guardianId + ",\"linkedToExisting\":false}"));
		assertThat(ofStudent(student.id(), MemberAudit.GUARDIAN_RELATION_CHANGED)).singleElement()
				.satisfies(each -> assertThat(each.getDetailJson())
						.isEqualTo("{\"guardianId\":" + guardianId + ",\"relation\":\"GRANDPARENT\"}"));
		assertThat(ofStudent(student.id(), MemberAudit.GUARDIAN_UNLINKED)).singleElement()
				.satisfies(each -> assertThat(each.getDetailJson()).isEqualTo("{\"guardianId\":" + guardianId + "}"));
	}

	// "그 번호가 이미 있다"는 응답을 되풀이해 받으면 가린 전화번호의 가운데 자리를 알아낼 수 있다(ADR-0023).
	// 이 요청들은 409로 끝나 롤백된다. 그래도 누가 받았는지는 남아야 한다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = STAFF)
	void 번호가_이미_있다고_알려_준_응답은_요청이_실패해도_기록된다() {
		PhoneNumber othersPhone = randomPhone();
		long othersGuardian = studentService.register(registration(uniqueName(), mother(othersPhone)))
				.guardians()
				.getFirst()
				.id();
		StudentView student = studentService.register(registration(uniqueName(), mother(randomPhone())));
		long guardianId = student.guardians().getFirst().id();

		assertFails(() -> guardianService.update(guardianId, "김어머니", othersPhone),
				MemberErrorCode.GUARDIAN_PHONE_DUPLICATED);
		assertFails(() -> guardianService.update(guardianId, "김어머니", othersPhone),
				MemberErrorCode.GUARDIAN_PHONE_DUPLICATED);
		studentService.addGuardian(student.id(), father(othersPhone));
		assertFails(() -> studentService.addGuardian(student.id(), father(othersPhone)),
				MemberErrorCode.GUARDIAN_ALREADY_LINKED);

		// 되풀이한 횟수만큼 남는다.
		assertThat(ofGuardian(guardianId, MemberAudit.GUARDIAN_PHONE_DUPLICATED)).hasSize(2).allSatisfy(each -> {
			assertThat(each.getActorId()).isEqualTo(STAFF);
			assertThat(each.getDetailJson()).isNull();
		});
		assertThat(ofStudent(student.id(), MemberAudit.GUARDIAN_ALREADY_LINKED)).singleElement().satisfies(each -> {
			assertThat(each.getActorId()).isEqualTo(STAFF);
			assertThat(each.getDetailJson()).isEqualTo("{\"guardianId\":" + othersGuardian + "}");
		});
		assertThat(ofStudent(student.id(), MemberAudit.GUARDIAN_LINKED)).extracting(AuditRecord::getDetailJson)
				.contains("{\"guardianId\":" + othersGuardian + ",\"linkedToExisting\":true}");
		// 거부된 수정은 "수정했다"로 남지 않는다.
		assertThat(ofGuardian(guardianId, MemberAudit.GUARDIAN_UPDATED)).isEmpty();
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = STAFF)
	void 학부모_수정은_번호를_바꿨는지와_함께_기록된다() {
		PhoneNumber original = randomPhone();
		long guardianId = studentService.register(registration(uniqueName(), mother(original)))
				.guardians()
				.getFirst()
				.id();

		guardianService.update(guardianId, "박어머니", null);
		// 같은 번호를 다시 보낸 것은 번호를 바꾼 것이 아니다.
		guardianService.update(guardianId, "박어머니", original);
		guardianService.update(guardianId, "박어머니", randomPhone());

		assertThat(ofGuardian(guardianId, MemberAudit.GUARDIAN_UPDATED)).extracting(AuditRecord::getDetailJson)
				.containsExactlyInAnyOrder("{\"phoneChanged\":false}", "{\"phoneChanged\":false}",
						"{\"phoneChanged\":true}");
	}

	// 기록은 오래 남고 관리자가 본다. 이름과 전화번호는 어디에도 실리지 않아야 한다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = STAFF)
	void 기록에는_이름과_전화번호가_없다() {
		PhoneNumber motherPhone = randomPhone();
		PhoneNumber studentPhone = randomPhone();
		PhoneNumber changed = randomPhone();
		String name = uniqueName();
		StudentView student = studentService.register(registrationWithPhone(name, studentPhone, mother(motherPhone)));
		long guardianId = student.guardians().getFirst().id();
		studentService.update(student.id(), change(student, changed));
		guardianService.update(guardianId, "이름바꿈", changed);
		await().atMost(TIMEOUT).untilAsserted(() -> assertThat(registeredEventOf(student.id())).hasSize(1));

		List<AuditRecord> all = new java.util.ArrayList<>(registeredEventOf(student.id()));
		all.addAll(auditLogService.search(null, null, "student", student.id(), null, null, 0, 100).getContent());
		all.addAll(auditLogService.search(null, null, "guardian", guardianId, null, null, 0, 100).getContent());

		assertThat(all).hasSizeGreaterThanOrEqualTo(4);
		assertThat(all).extracting(each -> String.valueOf(each.getDetailJson()))
				.noneMatch(detail -> detail.contains(name) || detail.contains("김어머니") || detail.contains("이름바꿈")
						|| detail.contains(motherPhone.value().substring(3))
						|| detail.contains(studentPhone.value().substring(3))
						|| detail.contains(changed.value().substring(3)) || detail.contains("****"));
	}

	private List<AuditRecord> ofStudent(long studentId, String action) {
		return auditLogService.search(null, action, MemberAudit.STUDENT, studentId, null, null, 0, 100).getContent();
	}

	private List<AuditRecord> ofGuardian(long guardianId, String action) {
		return auditLogService.search(null, action, MemberAudit.GUARDIAN, guardianId, null, null, 0, 100)
				.getContent();
	}

	// 이벤트 기록에는 대상 컬럼이 없다. 내용에 실린 학생 ID로 찾는다.
	private List<AuditRecord> registeredEventOf(long studentId) {
		return auditLogService.search(STAFF, StudentRegistered.TYPE, null, null, null, null, 0, 100)
				.getContent()
				.stream()
				.filter(each -> ("{\"studentId\":" + studentId + "}").equals(each.getDetailJson()))
				.toList();
	}

	private static StudentChange change(StudentView current, PhoneNumber phone) {
		return new StudentChange(current.campusId(), current.classId(), current.name(), current.school(),
				current.grade(), current.status(), current.registeredAt(), phone);
	}

}
