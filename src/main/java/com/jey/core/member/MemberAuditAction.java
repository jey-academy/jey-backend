package com.jey.core.member;

import com.jey.core.audit.api.AuditAction;
import com.jey.core.audit.api.AuditOutcome;

/**
 * 학생·학부모에 대해 감사 로그에 남기는 행위. 이름과 "한 일인지 거부된 시도인지"를 이 한 곳에서 정한다.
 * 이름은 이미 쌓인 기록에 적혀 있고 조회 조건으로 쓰이므로 바꾸지 않는다.
 *
 * <p>학생 등록은 여기에 없다. 도메인 이벤트({@code member.student-registered})로 기록된다.
 *
 * <p>거부된 시도 셋은 모두 "그 번호가 이미 있다"는 것이 응답으로 드러나는 경우다.
 * 그런 응답을 되풀이해 받으면 가린 전화번호의 가운데 자리를 알아낼 수 있어서(ADR-0023) 누가 받았는지 남긴다. 번호는 남기지 않는다.
 */
enum MemberAuditAction implements AuditAction {

	/** 상세의 {@code phoneChanged}가 참이면 본인 번호를 넣거나 바꾼 것이다. */
	STUDENT_UPDATED("member.student-updated", AuditOutcome.DONE),

	STUDENT_PHONE_REMOVED("member.student-phone-removed", AuditOutcome.DONE),

	/** 상세의 {@code linkedToExisting}이 참이면 입력한 번호의 학부모가 이미 있어서 그 학부모에 이은 것이다. */
	GUARDIAN_LINKED("member.guardian-linked", AuditOutcome.DONE),

	GUARDIAN_RELATION_CHANGED("member.guardian-relation-changed", AuditOutcome.DONE),

	GUARDIAN_UNLINKED("member.guardian-unlinked", AuditOutcome.DONE),

	/** 상세의 {@code phoneChanged}가 참이면 번호를 바꾼 것이다. */
	GUARDIAN_UPDATED("member.guardian-updated", AuditOutcome.DONE),

	/** 학부모 번호를 바꾸려 했는데 다른 학부모가 쓰는 번호였다. */
	GUARDIAN_PHONE_DUPLICATED("member.guardian-phone-duplicated", AuditOutcome.DENIED),

	/** 학생에게 붙이려던 번호의 학부모가 이미 그 학생에게 붙어 있었다. */
	GUARDIAN_ALREADY_LINKED("member.guardian-already-linked", AuditOutcome.DENIED),

	/** 등록하려는 이름의 학생이 입력한 번호의 학부모(또는 본인 번호)에게 이미 있었다. */
	STUDENT_DUPLICATED("member.student-duplicated", AuditOutcome.DENIED);

	private final String code;

	private final AuditOutcome outcome;

	MemberAuditAction(String code, AuditOutcome outcome) {
		this.code = code;
		this.outcome = outcome;
	}

	@Override
	public String code() {
		return code;
	}

	@Override
	public AuditOutcome outcome() {
		return outcome;
	}

}
