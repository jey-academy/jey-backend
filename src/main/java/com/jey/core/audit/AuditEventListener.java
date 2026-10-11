package com.jey.core.audit;

import com.jey.core.shared.DomainEvent;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * 모든 도메인 이벤트를 감사 로그에 적는다.
 *
 * <p>개별 이벤트 타입이 아니라 공통 봉투 타입을 구독한다. 그래야 {@code core}가 다른 모듈(동탄고 패스, 수납)의 이벤트 타입을 몰라도 된다.
 * 이벤트를 낸 트랜잭션이 커밋된 뒤 다른 스레드에서 돈다. 로그인 정보를 읽을 수 없으므로 누가 했는지는 이벤트 봉투에서 읽는다.
 * 실패하면 발행 기록에 남아 다시 실행되고, 같은 이벤트를 다시 받아도 한 줄만 남는다.
 */
@Component
class AuditEventListener {

	private final AuditLogService auditLogService;

	AuditEventListener(AuditLogService auditLogService) {
		this.auditLogService = auditLogService;
	}

	@ApplicationModuleListener
	void on(DomainEvent event) {
		auditLogService.recordEvent(event);
	}

}
