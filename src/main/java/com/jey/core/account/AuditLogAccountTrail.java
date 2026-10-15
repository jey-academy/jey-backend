package com.jey.core.account;

import com.jey.core.audit.api.AuditEntry;
import com.jey.core.audit.api.AuditLog;
import com.jey.core.audit.api.DeniedAttempts;
import com.jey.core.auth.api.AccountActivity;
import com.jey.core.auth.api.AccountAuditTrail;
import com.jey.core.auth.api.AuthRefs;
import com.jey.core.shared.BusinessException;
import org.springframework.stereotype.Component;

/**
 * 계정에 대해 한 일과 거부된 시도를 감사 로그에 적는다 (ADR-0030).
 *
 * <p>계정을 다루는 인증 패키지는 감사 로그를 직접 부를 수 없다(감사 로그가 인증 패키지를 쓰고 있어 서로 물린다).
 * 인증 패키지가 정한 인터페이스를 이 패키지가 구현해 둘을 잇는다. 이 패키지는 둘 다를 알고, 아무도 이 패키지를 모른다.
 */
@Component
class AuditLogAccountTrail implements AccountAuditTrail {

	private final AuditLog auditLog;

	AuditLogAccountTrail(AuditLog auditLog) {
		this.auditLog = auditLog;
	}

	@Override
	public void done(AccountActivity activity) {
		if (activity.kind().denied()) {
			throw new IllegalArgumentException("거부된 시도를 한 일로 적을 수 없다: " + activity.kind());
		}
		auditLog.record(entryOf(activity));
	}

	@Override
	public void denied(AccountActivity activity, BusinessException denial) {
		if (!activity.kind().denied()) {
			throw new IllegalArgumentException("한 일을 거부된 시도로 적을 수 없다: " + activity.kind());
		}
		DeniedAttempts.record(auditLog, () -> entryOf(activity), denial);
	}

	private static AuditEntry entryOf(AccountActivity activity) {
		AuditEntry entry = AuditEntry.of(AccountAuditAction.of(activity.kind()));
		if (activity.accountId() != null) {
			entry = entry.target(AuthRefs.account(activity.accountId()));
		}
		if (activity.campusId() != null) {
			entry = entry.campus(activity.campusId());
		}
		if (activity.role() != null) {
			entry = entry.with("role", activity.role());
		}
		if (activity.partnerId() != null) {
			entry = entry.with("partnerId", activity.partnerId());
		}
		if (activity.kind() == AccountActivity.Kind.UPDATED) {
			entry = entry.with("nameChanged", activity.nameChanged())
				.with("affiliationChanged", activity.affiliationChanged());
		}
		return entry;
	}

}
