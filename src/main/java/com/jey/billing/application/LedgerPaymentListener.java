package com.jey.billing.application;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

import com.jey.billing.api.PaymentCanceled;
import com.jey.billing.api.PaymentCompleted;

/**
 * 결제와 결제 취소를 장부에 반영한다. 결제가 커밋된 뒤 다른 스레드에서 돈다.
 *
 * <p>트랜잭션 없이 돈다({@code NOT_SUPPORTED}) — 기본(새 트랜잭션)이면 구글을 부르는 동안 DB 연결을 쥐게 된다.
 * DB 일은 {@link LedgerSyncService}가 짧은 트랜잭션으로 나눠 한다. 구글 장애와 거절은 반영 상태 줄에 남기고 예외를 던지지 않는다
 * (다시 시도는 그 줄로 한다 — 이벤트 재전송에 맡기지 않는다). 던지는 것은 DB를 읽고 쓰지 못했거나 결제 자료가 어긋난 경우뿐이다. 그때는 이벤트가 다시 오고,
 * 시도 중에 멈춘 줄은 {@code LedgerSync#STALE_AFTER} 뒤 스케줄러가 다시 맡는다.
 * 같은 이벤트를 다시 받아도 결과가 같다.
 */
@Component
class LedgerPaymentListener {

	private final LedgerSyncService ledger;

	LedgerPaymentListener(LedgerSyncService ledger) {
		this.ledger = ledger;
	}

	@ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
	void on(PaymentCompleted event) {
		ledger.recordPayment(event.paymentId());
	}

	@ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
	void on(PaymentCanceled event) {
		ledger.recordCancel(event.paymentId());
	}

}
