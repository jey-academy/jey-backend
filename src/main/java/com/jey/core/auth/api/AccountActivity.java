package com.jey.core.auth.api;

import java.util.Objects;

/**
 * 계정에 대해 한 일 또는 거부된 시도 하나. 기록하는 쪽({@link AccountAuditTrail})에 넘긴다.
 * 아이디와 비밀번호는 싣지 않는다. 누구의 계정인지는 ID로 안다.
 *
 * @param kind 무슨 일인지
 * @param accountId 대상 계정. 만들어지지 않은 계정에 대한 거부(아이디 중복)는 {@code null}이다
 * @param role 만든 계정 또는 고친 뒤의 역할. 그 밖의 일에서는 {@code null}이다
 * @param campusId 만든 계정의 소속 지점. 지점이 없거나 그 밖의 일이면 {@code null}이다
 * @param nameChanged 고치기에서 이름이 바뀌었는지
 * @param affiliationChanged 고치기에서 역할이나 지점이 바뀌었는지
 */
public record AccountActivity(Kind kind, Long accountId, UserRole role, Long campusId, boolean nameChanged,
		boolean affiliationChanged) {

	public AccountActivity {
		Objects.requireNonNull(kind, "kind");
	}

	public static AccountActivity of(Kind kind, long accountId) {
		return new AccountActivity(kind, accountId, null, null, false, false);
	}

	public static AccountActivity created(long accountId, UserRole role, Long campusId) {
		return new AccountActivity(Kind.CREATED, accountId, Objects.requireNonNull(role, "role"), campusId, false,
				false);
	}

	public static AccountActivity updated(long accountId, UserRole role, boolean nameChanged,
			boolean affiliationChanged) {
		return new AccountActivity(Kind.UPDATED, accountId, Objects.requireNonNull(role, "role"), null, nameChanged,
				affiliationChanged);
	}

	/** 이미 쓰는 아이디로 계정을 만들려 했다. 대상 계정이 없다. */
	public static AccountActivity loginIdDuplicated() {
		return new AccountActivity(Kind.LOGIN_ID_DUPLICATED, null, null, null, false, false);
	}

	/** 상수를 더하면 기록하는 쪽이 그것을 어떻게 적을지 정해야 컴파일된다. */
	public enum Kind {

		CREATED(false),
		UPDATED(false),
		DISABLED(false),
		ENABLED(false),
		/** 관리자가 다른 계정의 비밀번호를 다시 정했다. */
		PASSWORD_RESET(false),
		/** 본인이 자기 비밀번호를 바꿨다. */
		PASSWORD_CHANGED(false),
		LOGIN_ID_DUPLICATED(true),
		/** 자기 자신을 차단하거나, 자기 역할·지점을 바꾸거나, 자기 비밀번호를 "다시 정하기"로 바꾸려 했다. */
		SELF_CHANGE_DENIED(true),
		/** 마지막 남은 활성 관리자를 차단하거나 다른 역할로 바꾸려 했다. */
		LAST_ADMIN_DENIED(true),
		/** 자기 비밀번호를 바꾸려 했는데 지금 비밀번호가 틀렸다. */
		PASSWORD_CHANGE_MISMATCH(true),
		/** 틀린 지금 비밀번호가 한도를 넘어 비밀번호 변경이 막혔다. */
		PASSWORD_CHANGE_LIMITED(true);

		private final boolean denied;

		Kind(boolean denied) {
			this.denied = denied;
		}

		/** 한 일이 아니라 거부된 시도인지. */
		public boolean denied() {
			return denied;
		}

	}

}
