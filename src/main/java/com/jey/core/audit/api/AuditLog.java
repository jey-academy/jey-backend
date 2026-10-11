package com.jey.core.audit.api;

/**
 * 누가 언제 무엇을 했는지 남기는 창구(ADR-0024).
 *
 * <p>누가 했는지는 넘기지 않는다. 로그인 정보에서 읽는다. 로그인 없이 도는 코드에서 부르면 행위자가 비어 있는 기록이 된다.
 * 다른 모듈의 사건은 이 창구를 부르지 않아도 도메인 이벤트로 기록된다. 이 창구는 이벤트가 없는 일을 남길 때 쓴다.
 */
public interface AuditLog {

	/**
	 * 한 줄을 남긴다. 언제 어떻게 적히는지는 행위의 {@link AuditAction#outcome()}이 정한다.
	 *
	 * <ul>
	 * <li>{@link AuditOutcome#DONE}: 그 일의 트랜잭션 안에서 부른다. 같은 트랜잭션에 적히므로 그 일이 커밋돼야 기록도 남고,
	 * 기록하지 못하면 그 일도 실패한다. "했다"는 기록과 실제가 어긋나지 않는다.</li>
	 * <li>{@link AuditOutcome#DENIED}: 그 요청의 트랜잭션이 끝난 뒤에 부른다(거부 예외를 트랜잭션 밖에서 잡아서).
	 * 따로 커밋되므로 요청이 실패해도 남는다. 트랜잭션 안에서 부르면 그 트랜잭션이 DB 연결을 쥔 채 기록용 연결을 하나 더 기다리게 되고,
	 * 거부될 요청이 몰리면 연결 풀이 바닥난다.</li>
	 * </ul>
	 *
	 * @throws IllegalStateException {@code DONE}을 트랜잭션 밖에서, {@code DENIED}를 트랜잭션 안에서 불렀을 때
	 * @throws IllegalArgumentException 행위 이름의 형식이 틀리거나, 내용이 너무 크거나, 전화번호처럼 보이는 문자열이 있을 때
	 */
	void record(AuditEntry entry);

}
