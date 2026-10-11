package com.jey.core.member;

import java.time.Duration;
import java.util.List;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.audit.AuditLogService;
import com.jey.core.audit.api.AuditOutcome;
import com.jey.core.audit.domain.AuditRecord;
import com.jey.core.auth.api.UserRole;
import com.jey.core.member.api.StudentRegistered;
import com.jey.core.member.domain.Grade;
import com.jey.core.member.domain.GuardianRelation;
import com.jey.core.member.domain.StudentStatus;
import com.jey.core.member.api.MemberRefs;
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

	// 거부되는 등록을 시험하는 테스트 하나만 쓰는 계정. 그 계정의 기록을 통째로 셀 수 있다.
	private static final long DUPLICATING_STAFF = 4243;

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
		// 이벤트가 대상을 알려 주므로 "그 학생에게 있었던 일"로 찾으면 등록도 나온다.
		assertThat(registered.getTargetType()).isEqualTo(MemberRefs.STUDENT);
		assertThat(registered.getTargetId()).isEqualTo(created.id());
		// 학생에 대해 한 일은 학생의 지점으로 적힌다.
		assertThat(ofStudent(created.id(), MemberAuditAction.GUARDIAN_LINKED.code()))
				.allSatisfy(each -> assertThat(each.getCampusId()).isEqualTo(NEUNGDONG));
		long newGuardian = created.guardians().getLast().id();
		assertThat(ofStudent(created.id(), MemberAuditAction.GUARDIAN_LINKED.code())).extracting(AuditRecord::getDetailJson)
				.containsExactlyInAnyOrder("{\"guardianId\":" + existingGuardian + ",\"linkedToExisting\":true}",
						"{\"guardianId\":" + newGuardian + ",\"linkedToExisting\":false}");
		assertThat(ofStudent(created.id(), MemberAuditAction.GUARDIAN_LINKED.code()))
				.allSatisfy(each -> assertThat(each.getActorId()).isEqualTo(STAFF));
	}

	// 미리 확인에서 걸린 등록은 아무것도 남기지 않는다. 그 계정이 한 등록은 성공한 하나뿐이어야 한다.
	// 저장하다 실패한 경우(트랜잭션 롤백)는 MemberAuditEdgeTest가 본다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = DUPLICATING_STAFF)
	void 중복으로_거부된_등록은_이벤트와_연결_기록을_남기지_않는다() {
		PhoneNumber phone = randomPhone();
		String name = uniqueName();
		StudentView existing = studentService.register(registration(name, mother(phone)));

		assertFails(() -> studentService.register(registration(name, mother(phone))),
				MemberErrorCode.STUDENT_DUPLICATED);

		// 이 테스트만 쓰는 계정이다. 등록 이벤트와 연결 기록이 계정 전체에서 하나씩뿐이어야 한다.
		await().atMost(TIMEOUT)
				.untilAsserted(() -> assertThat(auditLogService
						.search(DUPLICATING_STAFF, StudentRegistered.TYPE, null, null, null, null, null, 0, 20)
						.getContent()).extracting(AuditRecord::getDetailJson)
						.containsExactly("{\"studentId\":" + existing.id() + "}"));
		assertThat(auditLogService
				.search(DUPLICATING_STAFF, MemberAuditAction.GUARDIAN_LINKED.code(), null, null, null, null, null, 0, 20)
				.getContent()).extracting(AuditRecord::getTargetId).containsExactly(existing.id());
		// 이름과 번호를 넣어 "이미 있다"는 답을 받은 것은 그 번호가 맞다는 것을 안 것이기도 하다. 거부로 남는다.
		assertThat(auditLogService
				.search(DUPLICATING_STAFF, MemberAuditAction.STUDENT_DUPLICATED.code(), AuditOutcome.DENIED, null, null,
						null, null, 0, 20)
				.getContent()).singleElement().satisfies(denied -> {
					assertThat(denied.getCampusId()).isEqualTo(DONGTAN);
					assertThat(denied.getTargetType()).isNull();
					assertThat(denied.getDetailJson()).isNull();
				});
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

		assertThat(ofStudent(student.id(), MemberAuditAction.STUDENT_UPDATED.code())).extracting(AuditRecord::getDetailJson)
				.containsExactlyInAnyOrder("{\"phoneChanged\":false}", "{\"phoneChanged\":true}");
		assertThat(ofStudent(student.id(), MemberAuditAction.STUDENT_PHONE_REMOVED.code())).singleElement().satisfies(each -> {
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

		assertThat(ofStudent(student.id(), MemberAuditAction.STUDENT_UPDATED.code())).isEmpty();
		assertThat(ofStudent(student.id(), MemberAuditAction.GUARDIAN_UNLINKED.code())).isEmpty();
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = STAFF)
	void 학부모_추가와_관계_수정과_연결_끊기가_기록된다() {
		StudentView student = studentService.register(registrationWithPhone(uniqueName(), randomPhone()));

		long guardianId = studentService.addGuardian(student.id(), father(randomPhone())).guardians().getFirst().id();
		studentService.changeRelation(student.id(), guardianId, GuardianRelation.GRANDPARENT);
		studentService.removeGuardian(student.id(), guardianId);

		assertThat(ofStudent(student.id(), MemberAuditAction.GUARDIAN_LINKED.code())).singleElement()
				.satisfies(each -> assertThat(each.getDetailJson())
						.isEqualTo("{\"guardianId\":" + guardianId + ",\"linkedToExisting\":false}"));
		assertThat(ofStudent(student.id(), MemberAuditAction.GUARDIAN_RELATION_CHANGED.code())).singleElement()
				.satisfies(each -> assertThat(each.getDetailJson())
						.isEqualTo("{\"guardianId\":" + guardianId + ",\"relation\":\"GRANDPARENT\"}"));
		assertThat(ofStudent(student.id(), MemberAuditAction.GUARDIAN_UNLINKED.code())).singleElement()
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
		assertThat(ofGuardian(guardianId, MemberAuditAction.GUARDIAN_PHONE_DUPLICATED.code())).hasSize(2).allSatisfy(each -> {
			assertThat(each.getActorId()).isEqualTo(STAFF);
			assertThat(each.getDetailJson()).isNull();
		});
		assertThat(ofStudent(student.id(), MemberAuditAction.GUARDIAN_ALREADY_LINKED.code())).singleElement().satisfies(each -> {
			assertThat(each.getActorId()).isEqualTo(STAFF);
			assertThat(each.getDetailJson()).isEqualTo("{\"guardianId\":" + othersGuardian + "}");
		});
		assertThat(ofStudent(student.id(), MemberAuditAction.GUARDIAN_LINKED.code())).extracting(AuditRecord::getDetailJson)
				.contains("{\"guardianId\":" + othersGuardian + ",\"linkedToExisting\":true}");
		// 거부된 수정은 "수정했다"로 남지 않는다.
		assertThat(ofGuardian(guardianId, MemberAuditAction.GUARDIAN_UPDATED.code())).isEmpty();
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

		assertThat(ofGuardian(guardianId, MemberAuditAction.GUARDIAN_UPDATED.code())).extracting(AuditRecord::getDetailJson)
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
		all.addAll(auditLogService.search(null, null, null, "student", student.id(), null, null, 0, 100).getContent());
		all.addAll(auditLogService.search(null, null, null, "guardian", guardianId, null, null, 0, 100).getContent());

		assertThat(all).hasSizeGreaterThanOrEqualTo(4);
		assertThat(all).extracting(each -> String.valueOf(each.getDetailJson()))
				.noneMatch(detail -> detail.contains(name) || detail.contains("김어머니") || detail.contains("이름바꿈")
						|| detail.contains(motherPhone.value().substring(3))
						|| detail.contains(studentPhone.value().substring(3))
						|| detail.contains(changed.value().substring(3)) || detail.contains("****"));
	}

	private List<AuditRecord> ofStudent(long studentId, String action) {
		return auditLogService.search(null, action, null, MemberRefs.STUDENT, studentId, null, null, 0, 100).getContent();
	}

	private List<AuditRecord> ofGuardian(long guardianId, String action) {
		return auditLogService.search(null, action, null, MemberRefs.GUARDIAN, guardianId, null, null, 0, 100)
				.getContent();
	}

	// 등록 이벤트는 그 학생을 대상으로 적힌다.
	private List<AuditRecord> registeredEventOf(long studentId) {
		return auditLogService
				.search(null, StudentRegistered.TYPE, null, MemberRefs.STUDENT, studentId, null, null, 0, 100)
				.getContent();
	}

	private static StudentChange change(StudentView current, PhoneNumber phone) {
		return new StudentChange(current.campusId(), current.classId(), current.name(), current.school(),
				current.grade(), current.status(), current.registeredAt(), phone);
	}

}
