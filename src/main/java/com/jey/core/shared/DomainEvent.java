package com.jey.core.shared;

import java.util.Optional;

/**
 * 모든 도메인 이벤트가 구현하는 공통 봉투. 이벤트는 봉투 정보와 자기 내용을 가진 record로 만든다.
 * <pre>
 * record PaymentCompleted(EventMetadata metadata, long paymentId, Money amount) implements DomainEvent {
 *     public Optional&lt;EntityRef&gt; subject() { return Optional.of(EntityRef.of("payment", paymentId)); }
 * }
 * </pre>
 * 감사 로그처럼 모든 이벤트를 받아야 하는 쪽은 개별 이벤트 타입 대신 이 타입을 구독한다.
 * 그래야 {@code core}가 다른 모듈의 이벤트 타입을 몰라도 된다.
 * 이벤트에는 개인정보를 넣지 않는다. ID만 넣고 필요한 쪽이 조회한다.
 */
public interface DomainEvent {

	EventMetadata metadata();

	/**
	 * 이 사건이 무엇에 대한 것인지(예: 학생 12번). 감사 로그가 대상으로 적어서 "그 학생에게 있었던 일"로 찾을 수 있게 한다.
	 *
	 * <p>기본 구현이 없다. 이벤트마다 대상이 있는지 없는지를 직접 적는다.
	 * 감사 로그는 추가만 하므로, 빠뜨린 채 쌓인 줄은 나중에 대상을 채울 수 없다.
	 * 이름이 {@code get}으로 시작하지 않아 JSON에는 실리지 않는다.
	 *
	 * @return 특정 대상이 없는 사건이면 비어 있다
	 */
	Optional<EntityRef> subject();

}
