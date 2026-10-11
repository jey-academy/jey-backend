package com.jey.core.audit.api;

import java.util.Map;

/**
 * 누가 언제 무엇을 했는지 남기는 창구(ADR-0024).
 *
 * <p>누가 했는지는 넘기지 않는다. 로그인 정보에서 읽는다. 로그인 없이 도는 코드에서 부르면 행위자가 비어 있는 기록이 된다.
 * 다른 모듈의 사건은 이 창구를 부르지 않아도 도메인 이벤트로 기록된다. 이 창구는 이벤트가 없는 일을 남길 때 쓴다.
 *
 * <p>기록에는 개인정보(전화번호, 이름)를 싣지 않는다. ID만 싣는다.
 * 상세에 전화번호처럼 보이는 문자열이 있으면 기록하지 않고 예외를 던진다.
 */
public interface AuditLog {

	/**
	 * 성공한 일을 남긴다. 부르는 쪽의 트랜잭션에 같이 적는다.
	 * 그 일이 커밋돼야 기록도 남고, 기록하지 못하면 그 일도 실패한다. "했다"는 기록과 실제가 어긋나지 않게 하기 위해서다.
	 *
	 * @param action {@code {모듈}.{사건}} 형식의 소문자 kebab-case(예: {@code member.guardian-unlinked}). 한 번 쓴 이름은 바꾸지 않는다
	 * @param target 대상. 특정 대상이 없으면 {@code null}
	 * @param detail 덧붙일 내용. 값은 숫자, 참·거짓, enum, 짧은 문자열만 된다. 없으면 빈 맵
	 * @throws IllegalArgumentException 행위 이름이나 상세가 규칙에 맞지 않을 때
	 */
	void record(String action, AuditTarget target, Map<String, ?> detail);

	/**
	 * 거부된 시도를 남긴다. 따로 커밋하므로 부르는 쪽이 그 뒤에 예외를 던져 롤백돼도 남는다.
	 * 거부된 요청은 예외로 끝나기 때문에 {@link #record}로 적으면 기록도 같이 사라진다.
	 *
	 * @throws IllegalArgumentException {@link #record}와 같은 조건
	 */
	void recordAttempt(String action, AuditTarget target, Map<String, ?> detail);

}
