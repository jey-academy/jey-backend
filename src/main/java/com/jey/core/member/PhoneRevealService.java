package com.jey.core.member;

import com.jey.core.audit.api.AuditEntry;
import com.jey.core.audit.api.AuditLog;
import com.jey.core.auth.api.AccountRateLimiter;
import com.jey.core.auth.api.CurrentActor;
import com.jey.core.auth.api.RateLimit;
import com.jey.core.member.api.MemberRefs;
import com.jey.core.member.domain.Guardian;
import com.jey.core.member.domain.GuardianRepository;
import com.jey.core.member.domain.Student;
import com.jey.core.member.domain.StudentGuardianRepository;
import com.jey.core.member.domain.StudentRepository;
import com.jey.core.shared.Actor;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.PhoneNumber;
import com.jey.core.shared.PhoneProtector;
import com.jey.core.shared.RateLimitedException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 가리지 않은 전화번호를 내준다(ADR-0026). 전체 번호가 나가는 길은 여기 하나다.
 *
 * <p>볼 때마다 누가 누구의 번호를 봤는지 감사 로그에 남긴다. 기록과 같은 트랜잭션에서 번호를 읽으므로, 기록하지 못하면 번호도 나가지 않는다.
 * 계정마다 볼 수 있는 횟수에 한도가 있다. 없는 대상을 찌른 요청도 한 번으로 센다.
 *
 * <p>트랜잭션 안에서 부르지 않는다. 한도에 막힌 사실은 트랜잭션 밖에서 적어야 한다.
 */
@Service
@EnableConfigurationProperties(MemberLimitProperties.class)
public class PhoneRevealService {

	private final StudentRepository students;

	private final StudentGuardianRepository links;

	private final GuardianRepository guardians;

	private final PhoneProtector protector;

	private final TransactionTemplate transaction;

	private final AuditLog auditLog;

	private final CurrentActor currentActor;

	private final AccountRateLimiter limiter;

	private final MemberLimitProperties limits;

	PhoneRevealService(StudentRepository students, StudentGuardianRepository links, GuardianRepository guardians,
			PhoneProtector protector, TransactionTemplate transaction, AuditLog auditLog, CurrentActor currentActor,
			AccountRateLimiter limiter, MemberLimitProperties limits) {
		this.students = students;
		this.links = links;
		this.guardians = guardians;
		this.protector = protector;
		this.transaction = transaction;
		this.auditLog = auditLog;
		this.currentActor = currentActor;
		this.limiter = limiter;
		this.limits = limits;
	}

	/**
	 * 학생 본인의 번호.
	 *
	 * @throws RateLimitedException 이 계정이 볼 수 있는 횟수를 넘었을 때
	 * @throws BusinessException 없는 학생이거나 본인 번호가 없는 학생일 때
	 */
	public PhoneNumber studentPhone(long studentId) {
		countView();
		return transaction.execute(status -> {
			Student student = students.findById(studentId)
				.orElseThrow(() -> new BusinessException(MemberErrorCode.STUDENT_NOT_FOUND));
			if (!student.hasPhone()) {
				throw new BusinessException(MemberErrorCode.PHONE_NOT_FOUND);
			}
			PhoneNumber phone = protector.reveal(student.getPhone());
			auditLog.record(AuditEntry.of(MemberAuditAction.STUDENT_PHONE_REVEALED)
				.target(MemberRefs.student(studentId))
				.campus(student.getCampusId()));
			return phone;
		});
	}

	/**
	 * 그 학생에게 연결된 학부모의 번호. 학부모 ID만으로는 볼 수 없다. 어느 학생의 화면에서 봤는지가 기록에 남는다.
	 *
	 * @throws RateLimitedException 이 계정이 볼 수 있는 횟수를 넘었을 때
	 * @throws BusinessException 없는 학생이거나 그 학생에게 연결되지 않은 학부모일 때
	 */
	public PhoneNumber guardianPhone(long studentId, long guardianId) {
		countView();
		return transaction.execute(status -> {
			if (!students.existsById(studentId)) {
				throw new BusinessException(MemberErrorCode.STUDENT_NOT_FOUND);
			}
			Guardian guardian = links.findByStudentIdAndGuardianId(studentId, guardianId)
				.flatMap(link -> guardians.findById(link.getGuardianId()))
				.orElseThrow(() -> new BusinessException(MemberErrorCode.GUARDIAN_NOT_FOUND));
			PhoneNumber phone = protector.reveal(guardian.getPhone());
			auditLog.record(AuditEntry.of(MemberAuditAction.GUARDIAN_PHONE_REVEALED)
				.target(MemberRefs.guardian(guardianId))
				.with("studentId", studentId));
			return phone;
		});
	}

	// 번호를 읽기 전에 센다. 읽은 뒤에 세면 동시에 보낸 요청이 모두 확인을 통과한다.
	private void countView() {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("PhoneRevealService는 트랜잭션 밖에서 불러야 한다");
		}
		if (!(currentActor.get() instanceof Actor.User viewer)) {
			throw new IllegalStateException("로그인한 계정 없이 전체 전화번호를 볼 수 없다");
		}
		RateLimit limit = limits.phoneReveal();
		limiter.tryAcquire(limit, viewer.accountId()).ifPresent(blocked -> {
			throw LimitBlocks.rejection(limiter, auditLog, limit, viewer.accountId(), blocked,
					MemberAuditAction.PHONE_REVEAL_LIMITED, MemberErrorCode.PHONE_REVEAL_LIMITED);
		});
	}

}
