package com.jey.core.member;

import com.jey.core.audit.api.AuditEntry;
import com.jey.core.audit.api.AuditLog;
import com.jey.core.shared.BusinessException;

/**
 * 거부된 요청을 감사 로그에 남긴다. 거부 예외를 그 요청의 트랜잭션 밖에서 잡은 자리에서 부른다.
 */
final class DeniedAttempts {

	private DeniedAttempts() {
	}

	/**
	 * 기록하지 못하면 원래의 거부(409) 대신 그 실패를 던진다.
	 * 기록 없이 "그 번호가 이미 있다"를 알려 주지 않기 위해서다. 원래의 거부는 그 실패에 붙여 로그에 같이 남게 한다.
	 */
	static void record(AuditLog auditLog, AuditEntry entry, BusinessException denial) {
		try {
			auditLog.record(entry);
		}
		catch (RuntimeException failure) {
			failure.addSuppressed(denial);
			throw failure;
		}
	}

}
