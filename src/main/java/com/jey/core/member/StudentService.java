package com.jey.core.member;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.jey.core.audit.api.AuditEntry;
import com.jey.core.audit.api.AuditLog;
import com.jey.core.audit.api.DeniedAttempts;
import com.jey.core.auth.api.CurrentActor;
import com.jey.core.campus.api.CampusCatalog;
import com.jey.core.campus.api.ClassInfo;
import com.jey.core.member.GuardianService.EnsuredGuardian;
import com.jey.core.member.StudentView.ContactView;
import com.jey.core.member.StudentView.GuardianView;
import com.jey.core.member.StudentView.SiblingView;
import com.jey.core.member.api.MemberRefs;
import com.jey.core.member.api.StudentRegistered;
import com.jey.core.member.domain.Guardian;
import com.jey.core.member.domain.GuardianRelation;
import com.jey.core.member.domain.GuardianRepository;
import com.jey.core.member.domain.PrimaryContactRule;
import com.jey.core.member.domain.Student;
import com.jey.core.member.domain.StudentGuardian;
import com.jey.core.member.domain.StudentGuardianRepository;
import com.jey.core.member.domain.StudentRepository;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.EventMetadata;
import com.jey.core.shared.PhoneNumber;
import com.jey.core.shared.PhoneProtector;
import com.jey.core.shared.RateLimitedException;
import com.jey.core.shared.UniqueKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 학생과 그 학생의 학부모 연결. 지점으로 묶지 않는다. 직원은 다른 지점의 학생도 보고 고친다.
 *
 * <p>여기서 돌려주는 전화번호는 모두 {@link PhoneNumber}다. 응답이나 로그로 나가면 가린 표시가 된다.
 * 가린 표시에 뒷자리가 필요해서 복호화는 하지만, 원문을 내보내는 메서드는 없다.
 */
@Service
public class StudentService {

	private static final Logger log = LoggerFactory.getLogger(StudentService.class);

	private static final String LINK_UNIQUE_KEY = "uk_core_student_guardian";

	private static final Sort BY_NAME = Sort.by("name", "id");

	private final StudentRepository students;

	private final GuardianRepository guardians;

	private final StudentGuardianRepository links;

	private final GuardianService guardianService;

	private final CampusCatalog catalog;

	private final PhoneProtector protector;

	private final TransactionTemplate transaction;

	private final Clock clock;

	private final AuditLog auditLog;

	private final CurrentActor currentActor;

	private final ApplicationEventPublisher events;

	private final PhoneProbeGuard probeGuard;

	StudentService(StudentRepository students, GuardianRepository guardians, StudentGuardianRepository links,
			GuardianService guardianService, CampusCatalog catalog, PhoneProtector protector,
			TransactionTemplate transaction, Clock clock, AuditLog auditLog, CurrentActor currentActor,
			ApplicationEventPublisher events, PhoneProbeGuard probeGuard) {
		this.probeGuard = probeGuard;
		this.auditLog = auditLog;
		this.currentActor = currentActor;
		this.events = events;
		this.students = students;
		this.guardians = guardians;
		this.links = links;
		this.guardianService = guardianService;
		this.catalog = catalog;
		this.protector = protector;
		this.transaction = transaction;
		this.clock = clock;
	}

	/**
	 * 학생을 등록한다. 같은 번호의 학부모가 이미 있으면 새로 만들지 않고 그 학부모에 붙인다.
	 *
	 * <p>트랜잭션 안에서 부르지 않는다. 학부모를 먼저 따로 커밋하고({@link GuardianService#findOrCreate}) 그 뒤에 학생과 연결을
	 * 한 트랜잭션으로 저장한다. 바깥에 트랜잭션이 열려 있으면 방금 커밋된 학부모가 그 트랜잭션에는 보이지 않는다.
	 *
	 * @throws BusinessException 연락처가 하나도 없거나, 같은 번호의 학부모를 두 번 적었거나, 지점·반이 틀렸거나,
	 * 같은 학부모에게 같은 이름의 학생이 이미 있을 때
	 * @throws RateLimitedException 이 계정이 "그 번호가 이미 있다"는 거부를 한도만큼 받아 막혀 있을 때({@link PhoneProbeGuard})
	 */
	public StudentView register(StudentRegistration registration) {
		requireNoTransaction("register");
		return probeGuard.guard(() -> registerRecordingDenial(registration));
	}

	private StudentView registerRecordingDenial(StudentRegistration registration) {
		try {
			return registerChecked(registration);
		}
		catch (BusinessException ex) {
			// 이름과 번호를 넣었을 때 "이미 있다"는 답은 그 번호가 맞다는 뜻이기도 하다. 누가 받았는지 남긴다.
			// 미리 확인에서 걸렸든 저장 직전에 걸렸든 여기서는 트랜잭션이 끝나 있다.
			if (ex.getErrorCode() == MemberErrorCode.STUDENT_DUPLICATED) {
				DeniedAttempts.record(auditLog,
						() -> AuditEntry.of(MemberAuditAction.STUDENT_DUPLICATED).campus(registration.campusId()), ex);
			}
			throw ex;
		}
	}

	private StudentView registerChecked(StudentRegistration registration) {
		List<GuardianInput> inputs = registration.guardians();
		if (inputs.isEmpty() && registration.phone() == null) {
			throw new BusinessException(MemberErrorCode.CONTACT_REQUIRED);
		}
		if (inputs.stream().map(GuardianInput::phone).distinct().count() != inputs.size()) {
			throw new BusinessException(MemberErrorCode.GUARDIAN_PHONE_REPEATED);
		}
		// 화면에서 온 요청은 요청 검증에서 먼저 걸린다. 여기서도 보는 것은 요청을 거치지 않고 부르는 경로(일괄 등록 등)를 위해서다.
		if (inputs.size() > StudentGuardian.MAX_PER_STUDENT) {
			throw new BusinessException(MemberErrorCode.TOO_MANY_GUARDIANS);
		}
		requireValidPlacement(registration.campusId(), registration.classId(), null);
		LocalDate registeredAt = (registration.registeredAt() != null) ? registration.registeredAt()
				: LocalDate.now(clock);
		// 엔티티를 먼저 만들어 값(이름 길이 등)을 확인한다. 틀린 요청 때문에 학부모만 생기는 일을 줄인다.
		Student student = Student.create(registration.campusId(), registration.classId(), registration.name(),
				registration.school(), registration.grade(), registeredAt,
				(registration.phone() != null) ? protector.protect(registration.phone()) : null);
		// 같은 요청이 두 번 온 경우를 학부모를 만들기 전에 걸러 낸다.
		requireNotDuplicated(student, !inputs.isEmpty(), existingGuardianIds(inputs));

		List<EnsuredGuardian> ensured = inputs.stream()
				.map(input -> guardianService.findOrCreate(input.name(), input.phone()))
				.toList();

		return transaction.execute(status -> {
			requireNotDuplicated(student, !inputs.isEmpty(),
					ensured.stream().map(EnsuredGuardian::guardianId).toList());
			Student saved = students.saveAndFlush(student);
			for (int i = 0; i < inputs.size(); i++) {
				links.save(StudentGuardian.link(saved.getId(), ensured.get(i).guardianId(), inputs.get(i).relation()));
			}
			links.flush();
			Set<Long> linkedToExisting = ensured.stream()
					.filter(EnsuredGuardian::existing)
					.map(EnsuredGuardian::guardianId)
					.collect(Collectors.toSet());
			ensured.forEach(each -> auditLog.record(studentEntry(MemberAuditAction.GUARDIAN_LINKED, saved)
					.with("guardianId", each.guardianId())
					.with("linkedToExisting", each.existing())));
			// 같은 트랜잭션에서 낸다. 등록이 커밋돼야 이벤트도 나가고, 리스너는 커밋 뒤에 돈다.
			events.publishEvent(new StudentRegistered(EventMetadata.create(StudentRegistered.TYPE,
					StudentRegistered.VERSION, saved.getCampusId(), currentActor.get(), clock),
					saved.getId()));
			return viewOf(saved, linkedToExisting);
		});
	}

	/**
	 * @throws BusinessException 없는 학생일 때
	 */
	@Transactional(readOnly = true)
	public StudentView get(long studentId) {
		return viewOf(students.findById(studentId)
				.orElseThrow(() -> new BusinessException(MemberErrorCode.STUDENT_NOT_FOUND)), Set.of());
	}

	/**
	 * 학생 목록. 조건은 모두 선택이고, 이름 순으로 준다. 퇴원한 학생도 포함한다.
	 *
	 * @param campusId {@code null}이면 전 지점
	 * @param name 이름의 앞부분. 비어 있으면 거르지 않는다
	 * @param phoneLast4 학부모나 학생 본인 번호의 뒷자리 4자리. 같은 뒷자리를 가진 사람이 모두 나오므로 결과는 후보 목록이다
	 * @param page 0부터
	 * @throws IllegalArgumentException 뒷자리가 숫자 4자리가 아닐 때
	 */
	@Transactional(readOnly = true)
	public Page<StudentSummaryView> search(Long campusId, String name, String phoneLast4, int page, int size) {
		String last4Index = (phoneLast4 != null) ? protector.last4Index(phoneLast4) : null;
		Page<Student> found = students.search(campusId, namePrefix(name), last4Index,
				PageRequest.of(page, size, BY_NAME));
		Map<Long, ContactView> contacts = primaryContacts(found.getContent());
		return found.map(student -> new StudentSummaryView(student.getId(), student.getCampusId(),
				student.getClassId(), student.getName(), student.getSchool(), student.getGrade(), student.getStatus(),
				contacts.get(student.getId())));
	}

	/**
	 * @throws BusinessException 없는 학생이거나 지점·반이 틀렸을 때
	 */
	@Transactional
	public StudentView update(long studentId, StudentChange change) {
		Student student = find(studentId);
		requireValidPlacement(change.campusId(), change.classId(), student);
		student.update(change.campusId(), change.classId(), change.name(), change.school(), change.grade(),
				change.status(), change.registeredAt());
		boolean phoneChanged = change.phone() != null && !sameNumber(student, change.phone());
		if (phoneChanged) {
			student.changePhone(protector.protect(change.phone()));
		}
		Student saved = students.saveAndFlush(student);
		auditLog.record(studentEntry(MemberAuditAction.STUDENT_UPDATED, saved).with("phoneChanged", phoneChanged));
		return viewOf(saved, Set.of());
	}

	/**
	 * 학생 본인 번호를 지운다. 원래 없었으면 아무 일도 하지 않는다.
	 *
	 * @throws BusinessException 없는 학생이거나, 학부모가 없어서 이 번호가 마지막 연락처일 때
	 */
	@Transactional
	public void removePhone(long studentId) {
		Student student = find(studentId);
		if (!student.hasPhone()) {
			return;
		}
		if (links.countByStudentId(studentId) == 0) {
			throw new BusinessException(MemberErrorCode.LAST_CONTACT);
		}
		student.removePhone();
		students.saveAndFlush(student);
		auditLog.record(studentEntry(MemberAuditAction.STUDENT_PHONE_REMOVED, student));
	}

	/**
	 * 학생에게 학부모를 붙인다. 같은 번호의 학부모가 있으면 그 학부모를, 없으면 새로 만들어 붙인다.
	 * {@link #register}와 같은 이유로 트랜잭션 안에서 부르지 않는다.
	 *
	 * @throws BusinessException 없는 학생이거나, 이미 붙어 있는 학부모이거나, 학부모 수가 상한({@link StudentGuardian#MAX_PER_STUDENT})에 찼을 때
	 * @throws RateLimitedException 이 계정이 "그 번호가 이미 있다"는 거부를 한도만큼 받아 막혀 있을 때
	 */
	public StudentView addGuardian(long studentId, GuardianInput input) {
		requireNoTransaction("addGuardian");
		return probeGuard.guard(() -> addGuardianRecordingDenial(studentId, input));
	}

	private StudentView addGuardianRecordingDenial(long studentId, GuardianInput input) {
		if (!students.existsById(studentId)) {
			throw new BusinessException(MemberErrorCode.STUDENT_NOT_FOUND);
		}
		// 학부모를 찾거나 만드는 일은 아래 트랜잭션보다 먼저 따로 커밋된다. 이미 꽉 찬 학생이면 그 전에 거부해서,
		// 어디에도 연결되지 않은 학부모 행이 생기는 일을 줄인다. 정확한 확인은 학생 행을 잠근 뒤에 한 번 더 한다.
		// 동시에 온 요청은 둘 다 이 확인을 통과할 수 있다. 그때 진 쪽이 만든 학부모는 연결 없이 남는다(아래에서 경고를 남긴다).
		requireRoomForGuardian(studentId);
		EnsuredGuardian ensured = guardianService.findOrCreate(input.name(), input.phone());
		try {
			return transaction.execute(status -> linkGuardian(studentId, ensured, input.relation()));
		}
		catch (BusinessException ex) {
			// 학생 행을 잠근 트랜잭션이 끝난 뒤에 남긴다. 안에서 남기면 잠금을 기다리는 요청들이 연결을 모두 쥐고 있어
			// 기록용 연결을 얻지 못하고 멈춘다.
			if (ex.getErrorCode() == MemberErrorCode.GUARDIAN_ALREADY_LINKED) {
				DeniedAttempts.record(auditLog, () -> AuditEntry.of(MemberAuditAction.GUARDIAN_ALREADY_LINKED)
						.target(MemberRefs.student(studentId))
						.with("guardianId", ensured.guardianId()), ex);
			}
			// 방금 만든 학부모인데 붙이지 못했다. 그 행은 어느 학생에게도 연결되지 않은 채 남는다.
			// 나중에 같은 번호가 들어오면 "이미 있는 학부모"로 나오는데, 어느 학생 화면에서도 찾을 수 없다. 흔적을 남겨 둔다.
			if (!ensured.existing()) {
				log.warn("새로 만든 학부모를 학생에게 붙이지 못했다. 연결 없는 학부모가 남는다: guardianId={} studentId={} code={}",
						ensured.guardianId(), studentId, ex.getErrorCode().code());
			}
			throw ex;
		}
	}

	private void requireRoomForGuardian(long studentId) {
		if (links.countByStudentId(studentId) >= StudentGuardian.MAX_PER_STUDENT) {
			throw new BusinessException(MemberErrorCode.TOO_MANY_GUARDIANS);
		}
	}

	private StudentView linkGuardian(long studentId, EnsuredGuardian ensured, GuardianRelation relation) {
		Student student = find(studentId);
		if (links.existsByStudentIdAndGuardianId(studentId, ensured.guardianId())) {
			throw new BusinessException(MemberErrorCode.GUARDIAN_ALREADY_LINKED);
		}
		// 학생 행을 잠근 뒤에 센다. 잠그기 전의 확인은 동시에 온 요청을 둘 다 통과시킨다.
		requireRoomForGuardian(studentId);
		try {
			links.saveAndFlush(StudentGuardian.link(studentId, ensured.guardianId(), relation));
		}
		catch (DataIntegrityViolationException ex) {
			// 위의 확인과 저장 사이에 같은 연결이 먼저 들어온 경우다.
			if (UniqueKeys.isViolationOf(ex, LINK_UNIQUE_KEY)) {
				throw new BusinessException(MemberErrorCode.GUARDIAN_ALREADY_LINKED, null, ex);
			}
			throw ex;
		}
		auditLog.record(studentEntry(MemberAuditAction.GUARDIAN_LINKED, student)
				.with("guardianId", ensured.guardianId())
				.with("linkedToExisting", ensured.existing()));
		return viewOf(student, ensured.existing() ? Set.of(ensured.guardianId()) : Set.of());
	}

	/**
	 * @throws BusinessException 없는 학생이거나 그 학생에게 붙어 있지 않은 학부모일 때
	 */
	@Transactional
	public StudentView changeRelation(long studentId, long guardianId, GuardianRelation relation) {
		Student student = find(studentId);
		StudentGuardian link = findLink(studentId, guardianId);
		link.changeRelation(relation);
		links.saveAndFlush(link);
		auditLog.record(studentEntry(MemberAuditAction.GUARDIAN_RELATION_CHANGED, student)
				.with("guardianId", guardianId)
				.with("relation", relation));
		return viewOf(student, Set.of());
	}

	/**
	 * 연결만 끊는다. 학부모는 남는다(다른 학생에게 붙어 있을 수 있다).
	 *
	 * @throws BusinessException 없는 학생이거나, 붙어 있지 않은 학부모이거나, 이 학부모가 마지막 연락처일 때
	 */
	@Transactional
	public void removeGuardian(long studentId, long guardianId) {
		Student student = find(studentId);
		StudentGuardian link = findLink(studentId, guardianId);
		if (!student.hasPhone() && links.countByStudentId(studentId) == 1) {
			throw new BusinessException(MemberErrorCode.LAST_CONTACT);
		}
		links.delete(link);
		links.flush();
		auditLog.record(studentEntry(MemberAuditAction.GUARDIAN_UNLINKED, student).with("guardianId", guardianId));
	}

	// 학생에 대해 한 일의 기록. 대상은 그 학생이고 지점은 학생의 지점이다.
	private static AuditEntry studentEntry(MemberAuditAction action, Student student) {
		return AuditEntry.of(action).target(MemberRefs.student(student.getId())).campus(student.getCampusId());
	}

	// 고치는 메서드는 모두 이것으로 학생을 읽는다. 학생 행을 잠가서 같은 학생을 고치는 요청이 줄을 서게 한다.
	// 잠그지 않으면 "남은 연락처를 세고 지우기"가 동시에 둘 다 통과해 연락처가 없는 학생이 생기고,
	// 수정이 읽어 둔 옛 값을 다시 써서 방금 지운 번호가 되살아난다. 각 메서드의 첫 조회여야 한다.
	private Student find(long studentId) {
		return students.findByIdForUpdate(studentId)
				.orElseThrow(() -> new BusinessException(MemberErrorCode.STUDENT_NOT_FOUND));
	}

	// 학부모를 따로 커밋한 뒤 학생을 저장하는 메서드는 바깥에 트랜잭션이 있으면 안 된다.
	// 방금 커밋된 학부모가 바깥 트랜잭션에는 보이지 않아, 한참 뒤에 원인을 알 수 없는 오류로 드러난다.
	private static void requireNoTransaction(String method) {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("StudentService#" + method + "는 트랜잭션 밖에서 불러야 한다");
		}
	}

	private StudentGuardian findLink(long studentId, long guardianId) {
		return links.findByStudentIdAndGuardianId(studentId, guardianId)
				.orElseThrow(() -> new BusinessException(MemberErrorCode.GUARDIAN_NOT_FOUND));
	}

	/**
	 * @param current 수정이면 지금의 학생. 반을 바꾸지 않았으면 그 반이 꺼져 있어도 둔다(반을 끈 뒤에도 학생 정보는 고칠 수 있어야 한다)
	 */
	private void requireValidPlacement(long campusId, Long classId, Student current) {
		if (!catalog.campusExists(campusId)) {
			throw new BusinessException(MemberErrorCode.INVALID_CAMPUS);
		}
		if (classId == null) {
			return;
		}
		ClassInfo found = catalog.findClass(classId)
				.orElseThrow(() -> new BusinessException(MemberErrorCode.INVALID_CLASS));
		boolean unchanged = current != null && classId.equals(current.getClassId());
		if (found.campusId() != campusId || (!found.active() && !unchanged)) {
			throw new BusinessException(MemberErrorCode.INVALID_CLASS);
		}
	}

	private List<Long> existingGuardianIds(List<GuardianInput> inputs) {
		List<Long> ids = new ArrayList<>();
		for (GuardianInput input : inputs) {
			guardians.findByPhoneIndex(protector.fullIndex(input.phone())).map(Guardian::getId).ifPresent(ids::add);
		}
		return ids;
	}

	// 같은 등록 요청이 두 번 와서 학생이 둘 생기는 것을 막는다. 같은 키로 다시 보낸 요청은 Idempotency-Key가 막지만(ADR-0027),
	// 키가 다른 두 요청(버튼을 두 번 누름, 두 화면에서 등록)은 여기서 막는다.
	// 동시에 온 두 요청은 둘 다 이 확인을 통과할 수 있다.
	// 학부모와 함께 등록하면 "그 학부모에게 같은 이름의 학생이 있는지"를, 학부모 없이 등록하면 "같은 이름과 같은 본인 번호"를 본다.
	private void requireNotDuplicated(Student student, boolean withGuardians, List<Long> guardianIds) {
		boolean duplicated = withGuardians
				? !guardianIds.isEmpty() && students.existsByNameAndAnyGuardian(student.getName(), guardianIds)
				: students.existsByNameAndPhoneIndex(student.getName(), student.getPhone().getFullIndex());
		if (duplicated) {
			throw new BusinessException(MemberErrorCode.STUDENT_DUPLICATED);
		}
	}

	private boolean sameNumber(Student student, PhoneNumber phone) {
		return student.hasPhone() && student.getPhone().getFullIndex().equals(protector.fullIndex(phone));
	}

	private StudentView viewOf(Student student, Set<Long> linkedToExisting) {
		List<StudentGuardian> studentLinks = links.findByStudentIdOrderByIdAsc(student.getId());
		Map<Long, Guardian> guardiansById = guardiansById(studentLinks);
		List<GuardianView> guardianViews = studentLinks.stream().map(link -> {
			Guardian guardian = guardianOf(link, guardiansById);
			return new GuardianView(guardian.getId(), guardian.getName(), link.getRelation(),
					protector.reveal(guardian.getPhone()), linkedToExisting.contains(guardian.getId()));
		}).toList();
		PhoneNumber phone = student.hasPhone() ? protector.reveal(student.getPhone()) : null;
		ContactView primary = PrimaryContactRule.chooseGuardian(studentLinks)
				.map(link -> guardianViews.get(studentLinks.indexOf(link)))
				.map(chosen -> ContactView.ofGuardian(chosen.name(), chosen.relation(), chosen.phone()))
				.orElseGet(() -> ownContact(student, phone));
		List<SiblingView> siblings = students.findSiblings(student.getId())
				.stream()
				.map(sibling -> new SiblingView(sibling.getId(), sibling.getName(), sibling.getStatus()))
				.toList();
		return new StudentView(student.getId(), student.getCampusId(), student.getClassId(), student.getName(),
				student.getSchool(), student.getGrade(), student.getStatus(), student.getRegisteredAt(), phone,
				guardianViews, primary, siblings);
	}

	// 한 페이지의 대표 연락처를 구한다. 학생 수만큼 조회하지 않고 연결과 학부모를 한 번씩만 읽는다.
	// 복호화는 대표 연락처만 한다.
	private Map<Long, ContactView> primaryContacts(List<Student> page) {
		if (page.isEmpty()) {
			return Map.of();
		}
		Map<Long, List<StudentGuardian>> linksByStudent = links
				.findByStudentIdInOrderByIdAsc(page.stream().map(Student::getId).toList())
				.stream()
				.collect(Collectors.groupingBy(StudentGuardian::getStudentId));
		Map<Long, StudentGuardian> chosen = new HashMap<>();
		linksByStudent.forEach((studentId, studentLinks) -> PrimaryContactRule.chooseGuardian(studentLinks)
				.ifPresent(link -> chosen.put(studentId, link)));
		Map<Long, Guardian> guardiansById = guardiansById(chosen.values());
		Map<Long, ContactView> contacts = new HashMap<>();
		for (Student student : page) {
			StudentGuardian link = chosen.get(student.getId());
			if (link != null) {
				Guardian guardian = guardianOf(link, guardiansById);
				contacts.put(student.getId(), ContactView.ofGuardian(guardian.getName(), link.getRelation(),
						protector.reveal(guardian.getPhone())));
			}
			else {
				contacts.put(student.getId(),
						ownContact(student, student.hasPhone() ? protector.reveal(student.getPhone()) : null));
			}
		}
		return contacts;
	}

	// 학부모가 없으면 학생 본인 번호가 대표다. 둘 다 없는 학생은 있어서는 안 된다(등록과 삭제가 막는다).
	// 그래도 자료가 그렇게 됐으면 대표 연락처 없이(null) 내보낸다. 예외를 던지면 그 학생 한 명 때문에
	// 같은 페이지의 다른 학생까지 볼 수 없게 된다. 조용히 넘기지는 않는다. 만날 때마다 경고를 남긴다.
	private static ContactView ownContact(Student student, PhoneNumber phone) {
		if (phone == null) {
			log.warn("학생에게 연락처가 하나도 없다. 대표 연락처 없이 내보낸다. 학부모나 본인 번호를 넣어야 한다: studentId={} campusId={}",
					student.getId(), student.getCampusId());
			return null;
		}
		return ContactView.ofStudent(student.getName(), phone);
	}

	private static Guardian guardianOf(StudentGuardian link, Map<Long, Guardian> guardiansById) {
		Guardian guardian = guardiansById.get(link.getGuardianId());
		if (guardian == null) {
			throw new IllegalStateException("학생 " + link.getStudentId() + "에게 연결된 학부모 " + link.getGuardianId()
					+ "를 읽지 못했다");
		}
		return guardian;
	}

	private Map<Long, Guardian> guardiansById(Collection<StudentGuardian> studentLinks) {
		Set<Long> ids = new HashSet<>();
		studentLinks.forEach(link -> ids.add(link.getGuardianId()));
		return guardians.findAllById(ids).stream().collect(Collectors.toMap(Guardian::getId, Function.identity()));
	}

	// like의 특수문자를 글자 그대로 찾게 한다. 이스케이프 문자는 '!'다(StudentRepository#search).
	private static String namePrefix(String name) {
		return Optional.ofNullable(name)
				.map(String::strip)
				.filter(value -> !value.isEmpty())
				.map(value -> value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%")
				.orElse(null);
	}

}
