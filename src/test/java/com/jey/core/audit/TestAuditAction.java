package com.jey.core.audit;

import java.util.UUID;

import com.jey.core.audit.api.AuditAction;
import com.jey.core.audit.api.AuditOutcome;

/**
 * 테스트에서 쓰는 행위. 테스트끼리 DB를 같이 쓰므로 이름을 매번 새로 만들어 그 이름으로 찾는다.
 */
public record TestAuditAction(String code, AuditOutcome outcome) implements AuditAction {

	public static TestAuditAction done() {
		return new TestAuditAction(newCode(), AuditOutcome.DONE);
	}

	public static TestAuditAction denied() {
		return new TestAuditAction(newCode(), AuditOutcome.DENIED);
	}

	public static String newCode() {
		return "audittest.case-" + UUID.randomUUID().toString().substring(0, 8);
	}

}
