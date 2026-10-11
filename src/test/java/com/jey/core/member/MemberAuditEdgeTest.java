package com.jey.core.member;

import java.time.Clock;
import java.util.List;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.audit.AuditLogService;
import com.jey.core.audit.api.AuditLog;
import com.jey.core.audit.api.AuditOutcome;
import com.jey.core.auth.api.CurrentActor;
import com.jey.core.audit.domain.AuditRecord;
import com.jey.core.auth.api.UserRole;
import com.jey.core.campus.api.CampusCatalog;
import com.jey.core.member.api.StudentRegistered;
import com.jey.core.member.domain.GuardianRepository;
import com.jey.core.member.domain.StudentGuardianRepository;
import com.jey.core.member.domain.StudentRepository;
import com.jey.core.member.api.MemberRefs;
import com.jey.core.shared.PhoneNumber;
import com.jey.core.shared.PhoneProtector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static com.jey.core.member.MemberFixtures.DONGTAN;
import static com.jey.core.member.MemberFixtures.father;
import static com.jey.core.member.MemberFixtures.mother;
import static com.jey.core.member.MemberFixtures.randomPhone;
import static com.jey.core.member.MemberFixtures.registration;
import static com.jey.core.member.MemberFixtures.registrationWithPhone;
import static com.jey.core.member.MemberFixtures.uniqueName;
import static com.jey.core.member.StudentRegistrationTest.assertFails;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

// 순서대로 보내는 요청은 모두 "미리 확인"에서 걸린다. 확인과 저장 사이에 다른 요청이 끼어든 경우에만 지나는 길이 따로 있고,
// 그 길에서도 거부 기록이 남아야 한다. 저장소의 확인만 "없음"으로 답하게 해서 그 길을 지나게 한다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class MemberAuditEdgeTest {

	private static final long STAFF = 6262;

	// 이 계정으로는 실패하는 등록 하나만 한다. 이 계정의 흔적이 하나도 없어야 한다.
	private static final long FAILING_STAFF = 6363;

	@Autowired
	PhoneProbeGuard probeGuard;

	@Autowired
	StudentService studentService;

	@Autowired
	GuardianService guardianService;

	@Autowired
	AuditLogService auditLogService;

	@Autowired
	StudentRepository students;

	@Autowired
	GuardianRepository guardians;

	@Autowired
	StudentGuardianRepository links;

	@Autowired
	CampusCatalog catalog;

	@Autowired
	PhoneProtector protector;

	@Autowired
	TransactionTemplate transaction;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	Clock clock;

	@Autowired
	AuditLog auditLog;

	@Autowired
	CurrentActor currentActor;

	@Autowired
	ApplicationEventPublisher events;

	@Autowired
	JdbcTemplate jdbc;

	// 행위 이름은 조회 조건이고 이미 쌓인 기록에 적혀 있다. 상수의 값이 바뀌면 옛 기록과 새 기록의 이름이 갈린다.
	@Test
	void 행위_이름과_대상_종류는_바뀌지_않는다() {
		assertThat(MemberAuditAction.STUDENT_UPDATED.code()).isEqualTo("member.student-updated");
		assertThat(MemberAuditAction.STUDENT_PHONE_REMOVED.code()).isEqualTo("member.student-phone-removed");
		assertThat(MemberAuditAction.GUARDIAN_LINKED.code()).isEqualTo("member.guardian-linked");
		assertThat(MemberAuditAction.GUARDIAN_RELATION_CHANGED.code()).isEqualTo("member.guardian-relation-changed");
		assertThat(MemberAuditAction.GUARDIAN_UNLINKED.code()).isEqualTo("member.guardian-unlinked");
		assertThat(MemberAuditAction.GUARDIAN_UPDATED.code()).isEqualTo("member.guardian-updated");
		assertThat(MemberAuditAction.GUARDIAN_PHONE_DUPLICATED.code()).isEqualTo("member.guardian-phone-duplicated");
		assertThat(MemberAuditAction.GUARDIAN_ALREADY_LINKED.code()).isEqualTo("member.guardian-already-linked");
		assertThat(MemberAuditAction.STUDENT_DUPLICATED.code()).isEqualTo("member.student-duplicated");
		assertThat(MemberAuditAction.STUDENT_PHONE_REVEALED.code()).isEqualTo("member.student-phone-revealed");
		assertThat(MemberAuditAction.GUARDIAN_PHONE_REVEALED.code()).isEqualTo("member.guardian-phone-revealed");
		assertThat(MemberAuditAction.PHONE_REVEAL_LIMITED.code()).isEqualTo("member.phone-reveal-limited");
		assertThat(MemberAuditAction.PHONE_PROBE_LIMITED.code()).isEqualTo("member.phone-probe-limited");
		assertThat(MemberAuditAction.PHONE_CHANGE_LIMITED.code()).isEqualTo("member.phone-change-limited");
		assertThat(StudentRegistered.TYPE).isEqualTo("member.student-registered");
		// 거부된 시도는 이것뿐이다. 한 일을 거부로, 거부를 한 일로 바꾸면 적히는 방식과 조회 결과가 달라진다.
		assertThat(MemberAuditAction.values()).filteredOn(each -> each.outcome() == AuditOutcome.DENIED)
				.containsExactlyInAnyOrder(MemberAuditAction.GUARDIAN_PHONE_DUPLICATED,
						MemberAuditAction.GUARDIAN_ALREADY_LINKED, MemberAuditAction.STUDENT_DUPLICATED,
						MemberAuditAction.PHONE_REVEAL_LIMITED, MemberAuditAction.PHONE_PROBE_LIMITED,
						MemberAuditAction.PHONE_CHANGE_LIMITED);
		assertThat(MemberRefs.STUDENT).isEqualTo("student");
		assertThat(MemberRefs.GUARDIAN).isEqualTo("guardian");
	}

	// 두 요청이 서로 다른 학부모를 같은 새 번호로 바꾸면 둘 다 "쓰는 사람 없음"을 본다. 뒤쪽은 유니크 키에 막힌다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = STAFF)
	void 확인을_지난_뒤_유니크_키에_막힌_번호_변경도_거부로_기록된다() {
		PhoneNumber othersPhone = randomPhone();
		studentService.register(registration(uniqueName(), mother(othersPhone)));
		long guardianId = studentService.register(registration(uniqueName(), mother(randomPhone())))
				.guardians()
				.getFirst()
				.id();
		GuardianRepository checkMisses = mock(GuardianRepository.class, delegatesTo(guardians));
		doReturn(false).when(checkMisses).existsOtherByPhoneIndex(any(), any());
		GuardianService racing = new GuardianService(checkMisses, protector, transactionManager, auditLog, probeGuard);

		assertFails(() -> racing.update(guardianId, "바뀐 이름", othersPhone),
				MemberErrorCode.GUARDIAN_PHONE_DUPLICATED);

		assertThat(ofGuardian(guardianId, MemberAuditAction.GUARDIAN_PHONE_DUPLICATED.code())).singleElement()
				.satisfies(each -> assertThat(each.getActorId()).isEqualTo(STAFF));
		assertThat(ofGuardian(guardianId, MemberAuditAction.GUARDIAN_UPDATED.code())).isEmpty();
		assertThat(guardians.findById(guardianId).orElseThrow().getName()).isEqualTo("김어머니");
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = STAFF)
	void 확인을_지난_뒤_유니크_키에_막힌_학부모_연결도_거부로_기록된다() {
		PhoneNumber phone = randomPhone();
		StudentView student = studentService.register(registration(uniqueName(), mother(phone)));
		long guardianId = student.guardians().getFirst().id();
		StudentGuardianRepository checkMisses = mock(StudentGuardianRepository.class, delegatesTo(links));
		doReturn(false).when(checkMisses).existsByStudentIdAndGuardianId(any(), any());
		StudentService racing = new StudentService(students, guardians, checkMisses, guardianService, catalog,
				protector, transaction, clock, auditLog, currentActor, events, probeGuard);

		assertFails(() -> racing.addGuardian(student.id(), father(phone)), MemberErrorCode.GUARDIAN_ALREADY_LINKED);

		assertThat(ofStudent(student.id(), MemberAuditAction.GUARDIAN_ALREADY_LINKED.code())).singleElement()
				.satisfies(each -> assertThat(each.getDetailJson()).isEqualTo("{\"guardianId\":" + guardianId + "}"));
		// 등록할 때의 연결 기록 하나뿐이다. 실패한 연결은 "이었다"로 남지 않는다.
		assertThat(ofStudent(student.id(), MemberAuditAction.GUARDIAN_LINKED.code())).hasSize(1);
		assertThat(studentService.get(student.id()).guardians()).hasSize(1);
	}

	// 등록이 트랜잭션 안에서 실패하면 학생, 연결 기록, 등록 이벤트가 모두 같이 사라져야 한다.
	// 이벤트의 발행 기록은 등록과 같은 트랜잭션에 적히므로 기다리지 않고 바로 셀 수 있다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = FAILING_STAFF)
	void 저장하다_실패한_등록은_학생도_기록도_이벤트도_남기지_않는다() {
		String name = uniqueName();
		// 학생, 연결, 감사 기록, 이벤트 발행이 모두 끝난 뒤(응답을 만들 때)에 실패하게 한다.
		// 앞에서 실패하면 기록과 이벤트는 되돌려진 것이 아니라 애초에 불리지 않은 것이라 확인하는 뜻이 없다.
		StudentGuardianRepository readFails = mock(StudentGuardianRepository.class, delegatesTo(links));
		doThrow(new IllegalStateException("연결 저장 실패")).when(readFails).findByStudentIdOrderByIdAsc(any());
		StudentService failing = new StudentService(students, guardians, readFails, guardianService, catalog,
				protector, transaction, clock, auditLog, currentActor, events, probeGuard);

		assertThatIllegalStateException()
				.isThrownBy(() -> failing.register(registration(name, mother(randomPhone()))))
				.withMessage("연결 저장 실패");

		assertThat(studentService.search(null, name, null, 0, 20)).isEmpty();
		assertThat(auditLogService.search(FAILING_STAFF, null, null, null, null, null, null, 0, 20)).isEmpty();
		assertThat(publicationsBy(FAILING_STAFF)).isZero();
	}

	// 위 테스트의 세는 방법이 실제로 등록 이벤트를 세는지 확인한다. 세지 못하면 위의 "0건"은 뜻이 없다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = STAFF + 1)
	void 성공한_등록은_발행_기록에_행위자와_함께_남는다() {
		int before = publicationsBy(STAFF + 1);

		studentService.register(registration(uniqueName(), mother(randomPhone())));

		// 리스너가 둘(감사 로그, 테스트용)이어도 이벤트 하나에 발행 기록이 적어도 하나 생긴다.
		assertThat(publicationsBy(STAFF + 1)).isGreaterThan(before);
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = STAFF)
	void 같은_번호를_다시_보낸_학생_수정은_번호를_바꾼_것이_아니다() {
		PhoneNumber phone = randomPhone();
		String name = uniqueName();
		StudentView student = studentService.register(registrationWithPhone(name, phone, mother(randomPhone())));

		studentService.update(student.id(), new StudentChange(student.campusId(), null, name, student.school(),
				student.grade(), student.status(), student.registeredAt(), phone));

		assertThat(ofStudent(student.id(), MemberAuditAction.STUDENT_UPDATED.code())).singleElement()
				.satisfies(each -> assertThat(each.getDetailJson()).isEqualTo("{\"phoneChanged\":false}"));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = STAFF)
	void 거부된_번호_삭제는_지웠다는_기록을_남기지_않는다() {
		StudentView student = studentService.register(registrationWithPhone(uniqueName(), randomPhone()));

		assertFails(() -> studentService.removePhone(student.id()), MemberErrorCode.LAST_CONTACT);

		assertThat(ofStudent(student.id(), MemberAuditAction.STUDENT_PHONE_REMOVED.code())).isEmpty();
	}

	private int publicationsBy(long actorId) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM event_publication WHERE serialized_event LIKE ? "
				+ "AND serialized_event LIKE ?", Integer.class, "%\"actorId\":" + actorId + "%",
				"%" + StudentRegistered.TYPE + "%");
	}

	private List<AuditRecord> ofStudent(long studentId, String action) {
		return auditLogService.search(null, action, null, MemberRefs.STUDENT, studentId, null, null, 0, 100).getContent();
	}

	private List<AuditRecord> ofGuardian(long guardianId, String action) {
		return auditLogService.search(null, action, null, MemberRefs.GUARDIAN, guardianId, null, null, 0, 100)
				.getContent();
	}

}
