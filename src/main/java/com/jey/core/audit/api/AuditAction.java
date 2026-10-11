package com.jey.core.audit.api;

/**
 * 기록할 행위. 모듈마다 enum으로 구현한다({@code ErrorCode}와 같은 방식).
 *
 * <p>그 행위가 한 일인지 거부된 시도인지를 여기서 정한다. 기록하는 자리마다 고르지 않는다.
 * 그래야 거부된 시도를 한 일처럼 적거나 그 반대로 적는 실수가 생기지 않는다.
 */
public interface AuditAction {

	int MAX_CODE_LENGTH = 80;

	/**
	 * {@code {모듈}.{사건}} 형식의 소문자 kebab-case(예: {@code member.guardian-unlinked}). 도메인 이벤트의 이름과 같은 형식이다.
	 * 이미 쌓인 기록에 적혀 있고 조회 조건으로 쓰이므로 한 번 쓴 값은 바꾸지 않는다.
	 */
	String code();

	AuditOutcome outcome();

}
