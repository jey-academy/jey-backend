package com.jey.core.audit.api;

import java.util.function.Supplier;

import com.jey.core.shared.BusinessException;

/**
 * 거부된 요청을 감사 로그에 남긴다. 거부 예외를 그 요청의 트랜잭션 밖에서 잡은 자리에서 부른다.
 */
public final class DeniedAttempts {

	private DeniedAttempts() {
	}

	/**
	 * 기록하지 못하면 원래의 거부 대신 그 실패를 던진다.
	 * 기록 없이 "거부됐다"는 응답(예: 그 번호가 이미 있다)을 내보내지 않기 위해서다. 원래의 거부는 그 실패에 붙여 로그에 같이 남게 한다.
	 *
	 * @param entry 적을 내용을 만드는 방법. 만들다 실패해도 원래의 거부가 로그에 남도록 여기서 부른다
	 */
	public static void record(AuditLog auditLog, Supplier<AuditEntry> entry, BusinessException denial) {
		try {
			auditLog.record(entry.get());
		}
		catch (RuntimeException failure) {
			failure.addSuppressed(denial);
			throw failure;
		}
	}

}
