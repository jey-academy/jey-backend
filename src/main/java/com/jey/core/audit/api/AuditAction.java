package com.jey.core.audit.api;

import com.jey.core.shared.EventMetadata;

/**
 * 기록할 행위. 모듈마다 enum으로 구현한다({@code ErrorCode}와 같은 방식).
 *
 * <p>그 행위가 한 일인지 거부된 시도인지를 여기서 정한다. 기록하는 자리마다 고르지 않는다.
 * 그래야 거부된 시도를 한 일처럼 적거나 그 반대로 적는 실수가 생기지 않는다.
 */
public interface AuditAction {

	// 이벤트 이름과 같은 컬럼에 저장되므로 길이도 같다.
	int MAX_CODE_LENGTH = EventMetadata.MAX_EVENT_TYPE_LENGTH;

	/**
	 * {@code {모듈}.{사건}} 형식의 소문자 kebab-case(예: {@code member.guardian-unlinked}). 도메인 이벤트의 이름과 같은 형식이다.
	 * 이미 쌓인 기록에 적혀 있고 조회 조건으로 쓰이므로 한 번 쓴 값은 바꾸지 않는다.
	 * 도메인 이벤트의 이름과 겹치지 않아야 한다. 이벤트 이름도 같은 컬럼에 행위 이름으로 저장된다.
	 */
	String code();

	/**
	 * 한 번 정한 결과는 바꾸지 않는다. 바꾸면 같은 이름의 옛 기록과 새 기록의 뜻이 갈린다.
	 */
	AuditOutcome outcome();

}
