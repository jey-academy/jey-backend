package com.jey.core.shared;

// 봉투 발행·구독을 검증하기 위한 테스트 전용 이벤트. 실제 도메인 이벤트와 같은 모양(봉투 정보 + 내용)이다.
record TestDomainEvent(EventMetadata metadata, long targetId) implements DomainEvent {
}
