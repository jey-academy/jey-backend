package com.jey.core.audit.api;

/**
 * 그 일이 됐는지, 거부됐는지. 기록에 저장되고 조회 조건으로 쓰인다.
 */
public enum AuditOutcome {

	/** 실제로 한 일. 그 일의 트랜잭션에 같이 적힌다. 그 일이 롤백되면 기록도 사라진다. */
	DONE,

	/** 거부된 시도. 그 요청의 트랜잭션이 끝난 뒤 따로 적힌다. 요청이 실패해도 남는다. */
	DENIED

}
