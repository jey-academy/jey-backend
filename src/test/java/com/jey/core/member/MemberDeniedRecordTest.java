package com.jey.core.member;

import java.time.Clock;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.audit.AuditLogService;
import com.jey.core.audit.api.AuditEntry;
import com.jey.core.audit.api.AuditLog;
import com.jey.core.audit.api.AuditOutcome;
import com.jey.core.audit.api.DeniedAttempts;
import com.jey.core.auth.api.CurrentActor;
import com.jey.core.auth.api.UserRole;
import com.jey.core.campus.api.CampusCatalog;
import com.jey.core.member.api.StudentRegistered;
import com.jey.core.member.domain.GuardianRepository;
import com.jey.core.member.domain.StudentGuardianRepository;
import com.jey.core.member.domain.StudentRepository;
import com.jey.core.shared.BusinessException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

// 거부된 요청의 기록에 관한 것 가운데, 평소의 길로는 지나지 않는 경우들.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class MemberDeniedRecordTest {

	// 이 계정으로는 거부되는 등록만 한다. 이 계정의 기록을 통째로 셀 수 있다.
	private static final long RACING_STAFF = 7171;

	private static final long OWN_PHONE_STAFF = 7272;

	private static final long STAFF = 7373;

	@Autowired
	PhoneProbeGuard probeGuard;

	@Autowired
	StudentService studentService;

	@Autowired
	GuardianService guardianService;

	@Autowired
	AuditLogService auditLogService;

	@Autowired
	AuditLog auditLog;

	@Autowired
	CurrentActor currentActor;

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
	ApplicationEventPublisher events;

	@Autowired
	JdbcTemplate jdbc;

	// "그 번호가 이미 있다"는 답은 기록과 함께만 나간다. 기록하지 못했는데 409가 나가면, 기록 장치를 망가뜨린 쪽은 흔적 없이 번호를 맞혀 볼 수 있다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = STAFF)
	void 거부를_기록하지_못하면_거부_대신_그_실패가_나가고_원래의_거부는_거기에_붙는다() {
		PhoneNumber othersPhone = randomPhone();
		studentService.register(registration(uniqueName(), mother(othersPhone)));
		long guardianId = studentService.register(registration(uniqueName(), mother(randomPhone())))
				.guardians()
				.getFirst()
				.id();
		AuditLog deniedFails = mock(AuditLog.class, delegatesTo(auditLog));
		IllegalStateException failure = new IllegalStateException("기록 실패");
		doThrow(failure).when(deniedFails)
				.record(argThat(entry -> entry != null && entry.action().outcome() == AuditOutcome.DENIED));
		GuardianService service = new GuardianService(guardians, protector, transactionManager, deniedFails, probeGuard);

		assertThatThrownBy(() -> service.update(guardianId, "바뀐 이름", othersPhone)).isSameAs(failure)
				.satisfies(thrown -> assertThat(thrown.getSuppressed()).singleElement()
						.isInstanceOfSatisfying(BusinessException.class, denial -> assertThat(denial.getErrorCode())
								.isEqualTo(MemberErrorCode.GUARDIAN_PHONE_DUPLICATED)));

		assertThat(guardians.findById(guardianId).orElseThrow().getName()).isEqualTo("김어머니");
	}

	@Test
	void 적을_내용을_만들다_실패해도_원래의_거부가_같이_남는다() {
		BusinessException denial = new BusinessException(MemberErrorCode.GUARDIAN_ALREADY_LINKED);

		assertThatThrownBy(() -> DeniedAttempts.record(auditLog,
				() -> AuditEntry.of(MemberAuditAction.GUARDIAN_ALREADY_LINKED).target("student", 0), denial))
				.isInstanceOf(IllegalArgumentException.class)
				.satisfies(thrown -> assertThat(thrown.getSuppressed()).containsExactly(denial));
	}

	// 미리 확인을 지난 뒤, 저장 직전의 두 번째 확인에서 걸린 경우다. 학부모는 이미 따로 커밋된 뒤다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = RACING_STAFF)
	void 저장_직전의_확인에서_걸린_중복_등록도_거부로_기록된다() {
		PhoneNumber phone = randomPhone();
		String name = uniqueName();
		studentService.register(registration(name, mother(phone)));
		// 첫 확인만 "없음"으로 답하게 해서 두 번째 확인까지 가게 한다.
		StudentRepository firstCheckMisses = mock(StudentRepository.class, delegatesTo(students));
		doReturn(false)
				.doAnswer(call -> students.existsByNameAndAnyGuardian(call.getArgument(0), call.getArgument(1)))
				.when(firstCheckMisses)
				.existsByNameAndAnyGuardian(any(), any());
		StudentService racing = new StudentService(firstCheckMisses, guardians, links, guardianService, catalog,
				protector, transaction, clock, auditLog, currentActor, events, probeGuard);

		assertFails(() -> racing.register(registration(name, mother(phone))), MemberErrorCode.STUDENT_DUPLICATED);

		assertThat(auditLogService.search(RACING_STAFF, null, AuditOutcome.DENIED, null, null, null, null, 0, 20)
				.getContent()).singleElement().satisfies(denied -> {
					assertThat(denied.getAction()).isEqualTo("member.student-duplicated");
					assertThat(denied.getCampusId()).isEqualTo(DONGTAN);
				});
		// 학생, 연결 기록, 등록 이벤트는 먼저 성공한 등록의 것 하나씩뿐이다.
		assertThat(studentService.search(null, name, null, 0, 20)).hasSize(1);
		assertThat(auditLogService
				.search(RACING_STAFF, "member.guardian-linked", null, null, null, null, null, 0, 20)
				.getTotalElements()).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT serialized_event) FROM event_publication "
				+ "WHERE serialized_event LIKE ? AND serialized_event LIKE ?", Integer.class,
				"%\"actorId\":" + RACING_STAFF + "%", "%" + StudentRegistered.TYPE + "%")).isEqualTo(1);
	}

	// 학부모 없이 본인 번호로 등록한 학생도 같은 이름과 같은 번호면 중복이고, 그 답도 번호가 맞다는 뜻이다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = OWN_PHONE_STAFF)
	void 본인_번호로_등록한_학생의_중복도_거부로_기록된다() {
		PhoneNumber phone = randomPhone();
		String name = uniqueName();
		studentService.register(registrationWithPhone(name, phone));

		assertFails(() -> studentService.register(registrationWithPhone(name, phone)),
				MemberErrorCode.STUDENT_DUPLICATED);

		assertThat(auditLogService
				.search(OWN_PHONE_STAFF, "member.student-duplicated", AuditOutcome.DENIED, null, null, null, null, 0, 20)
				.getTotalElements()).isEqualTo(1);
	}

	// 거부 기록은 요청의 트랜잭션이 끝난 뒤에 적는다. 트랜잭션 안에서 부르면 그것이 성립하지 않으므로 시작할 때 막는다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = STAFF)
	void 학부모_수정은_트랜잭션_안에서_부를_수_없다() {
		long guardianId = studentService.register(registration(uniqueName(), father(randomPhone())))
				.guardians()
				.getFirst()
				.id();

		assertThatIllegalStateException()
				.isThrownBy(() -> transaction.execute(status -> guardianService.update(guardianId, "바뀐 이름", null)))
				.withMessageContaining("트랜잭션 밖");

		assertThat(guardians.findById(guardianId).orElseThrow().getName()).isEqualTo("김아버지");
	}

}
