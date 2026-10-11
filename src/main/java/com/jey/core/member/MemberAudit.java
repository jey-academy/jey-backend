package com.jey.core.member;

import java.util.Map;

import com.jey.core.audit.api.AuditLog;
import com.jey.core.audit.api.AuditTarget;
import org.springframework.stereotype.Component;

/**
 * 학생·학부모에 대해 한 일을 감사 로그에 남긴다. 행위 이름과 대상 종류를 이 한 곳에 모은다.
 * 이름을 흩어 쓰면 오타 하나로 그 행위가 조회에서 조용히 빠진다.
 *
 * <p>학생 등록은 여기서 남기지 않는다. 도메인 이벤트({@code member.student-registered})로 기록된다.
 *
 * <p>{@code *Attempt}는 거부된 시도다. 따로 커밋되어 요청이 실패해도 남는다.
 * "그 번호의 학부모가 이미 있다"는 응답을 되풀이해 받으면 가린 전화번호의 가운데 자리를 알아낼 수 있다(ADR-0023).
 * 그런 응답을 누가 받았는지 남겨서 되풀이가 보이게 한다. 번호는 남기지 않는다.
 */
@Component
class MemberAudit {

	static final String STUDENT_UPDATED = "member.student-updated";

	static final String STUDENT_PHONE_REMOVED = "member.student-phone-removed";

	/** 상세의 {@code linkedToExisting}이 참이면 입력한 번호의 학부모가 이미 있어서 그 학부모에 이은 것이다. */
	static final String GUARDIAN_LINKED = "member.guardian-linked";

	static final String GUARDIAN_RELATION_CHANGED = "member.guardian-relation-changed";

	static final String GUARDIAN_UNLINKED = "member.guardian-unlinked";

	/** 상세의 {@code phoneChanged}가 참이면 번호를 바꾼 것이다. */
	static final String GUARDIAN_UPDATED = "member.guardian-updated";

	/** 거부된 시도. 학부모 번호를 바꾸려 했는데 다른 학부모가 쓰는 번호였다. */
	static final String GUARDIAN_PHONE_DUPLICATED = "member.guardian-phone-duplicated";

	/** 거부된 시도. 학생에게 붙이려던 번호의 학부모가 이미 그 학생에게 붙어 있었다. */
	static final String GUARDIAN_ALREADY_LINKED = "member.guardian-already-linked";

	static final String STUDENT = "student";

	static final String GUARDIAN = "guardian";

	private final AuditLog auditLog;

	MemberAudit(AuditLog auditLog) {
		this.auditLog = auditLog;
	}

	void student(String action, long studentId, Map<String, ?> detail) {
		auditLog.record(action, AuditTarget.of(STUDENT, studentId), detail);
	}

	void guardian(String action, long guardianId, Map<String, ?> detail) {
		auditLog.record(action, AuditTarget.of(GUARDIAN, guardianId), detail);
	}

	void studentAttempt(String action, long studentId, Map<String, ?> detail) {
		auditLog.recordAttempt(action, AuditTarget.of(STUDENT, studentId), detail);
	}

	void guardianAttempt(String action, long guardianId) {
		auditLog.recordAttempt(action, AuditTarget.of(GUARDIAN, guardianId), Map.of());
	}

}
