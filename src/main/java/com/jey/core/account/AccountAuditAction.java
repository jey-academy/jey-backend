package com.jey.core.account;

import com.jey.core.audit.api.AuditAction;
import com.jey.core.audit.api.AuditOutcome;
import com.jey.core.auth.api.AccountActivity;

/**
 * 계정에 대해 감사 로그에 남기는 행위 (ADR-0030). 대상은 그 계정이다({@code AuthRefs.account}).
 * 이름은 이미 쌓인 기록에 적혀 있고 조회 조건으로 쓰이므로 바꾸지 않는다.
 *
 * <p>아이디와 비밀번호는 어떤 행위에도 싣지 않는다. 누구의 계정인지는 대상의 ID로 안다.
 */
enum AccountAuditAction implements AuditAction {

	/** 상세의 {@code role}이 만든 계정의 역할이다. 직원이면 지점이 함께 적힌다. */
	CREATED("account.created", AuditOutcome.DONE),

	/** 상세의 {@code nameChanged}, {@code affiliationChanged}가 무엇을 고쳤는지다. {@code role}은 고친 뒤의 역할이다. */
	UPDATED("account.updated", AuditOutcome.DONE),

	DISABLED("account.disabled", AuditOutcome.DONE),

	ENABLED("account.enabled", AuditOutcome.DONE),

	/** 관리자가 다른 계정의 비밀번호를 다시 정했다. */
	PASSWORD_RESET("account.password-reset", AuditOutcome.DONE),

	/** 본인이 자기 비밀번호를 바꿨다. */
	PASSWORD_CHANGED("account.password-changed", AuditOutcome.DONE),

	/** 이미 쓰는 아이디로 계정을 만들려 했다. 대상은 없다(만들어지지 않았다). */
	LOGIN_ID_DUPLICATED("account.login-id-duplicated", AuditOutcome.DENIED),

	/** 자기 자신을 차단하거나, 자기 역할·지점을 바꾸거나, 자기 비밀번호를 "다시 정하기"로 바꾸려 했다. */
	SELF_CHANGE_DENIED("account.self-change-denied", AuditOutcome.DENIED),

	/** 마지막 남은 활성 관리자를 차단하거나 다른 역할로 바꾸려 했다. */
	LAST_ADMIN_DENIED("account.last-admin-denied", AuditOutcome.DENIED),

	/** 자기 비밀번호를 바꾸려 했는데 지금 비밀번호가 틀렸다. */
	PASSWORD_CHANGE_MISMATCH("account.password-change-mismatch", AuditOutcome.DENIED),

	/** 틀린 지금 비밀번호가 한도를 넘어 비밀번호 변경이 막혔다. 구간마다 처음 막힌 때만 남는다. */
	PASSWORD_CHANGE_LIMITED("account.password-change-limited", AuditOutcome.DENIED);

	private final String code;

	private final AuditOutcome outcome;

	AccountAuditAction(String code, AuditOutcome outcome) {
		this.code = code;
		this.outcome = outcome;
	}

	// 계정을 다루는 쪽이 알려 온 일을 어느 행위로 적을지. 종류가 늘면 여기서 컴파일 오류가 난다.
	static AccountAuditAction of(AccountActivity.Kind kind) {
		return switch (kind) {
			case CREATED -> CREATED;
			case UPDATED -> UPDATED;
			case DISABLED -> DISABLED;
			case ENABLED -> ENABLED;
			case PASSWORD_RESET -> PASSWORD_RESET;
			case PASSWORD_CHANGED -> PASSWORD_CHANGED;
			case LOGIN_ID_DUPLICATED -> LOGIN_ID_DUPLICATED;
			case SELF_CHANGE_DENIED -> SELF_CHANGE_DENIED;
			case LAST_ADMIN_DENIED -> LAST_ADMIN_DENIED;
			case PASSWORD_CHANGE_MISMATCH -> PASSWORD_CHANGE_MISMATCH;
			case PASSWORD_CHANGE_LIMITED -> PASSWORD_CHANGE_LIMITED;
		};
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
