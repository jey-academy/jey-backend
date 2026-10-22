package com.jey.dongtanpass.application;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

import com.jey.dongtanpass.api.PartnerPassRedeemed;
import com.jey.dongtanpass.api.PassRedeemed;
import com.jey.dongtanpass.domain.PartnerLedgerKind;

/**
 * 패스 사용을 연계 학원 장부에 반영한다. 사용 처리가 커밋된 뒤 다른 스레드에서 돈다.
 *
 * <p>트랜잭션 없이 돈다({@code NOT_SUPPORTED}) — 기본(새 트랜잭션)이면 구글을 부르는 동안 DB 연결을 쥐게 된다.
 * 구글 장애와 거절은 반영 상태 줄에 남기고 예외를 던지지 않는다(다시 시도는 그 줄로 한다). 던지는 것은 DB를 읽고 쓰지 못한 경우뿐이다.
 * 같은 이벤트를 다시 받아도 결과가 같다.
 */
@Component
class PartnerLedgerListener {

	private final PartnerLedgerService ledger;

	PartnerLedgerListener(PartnerLedgerService ledger) {
		this.ledger = ledger;
	}

	/** 연계 학원이 JEY 발급 패스로 할인해 주었다 → 그 학원의 장부. */
	@ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
	void on(PartnerPassRedeemed event) {
		ledger.record(event.passId(), event.partnerId(), PartnerLedgerKind.PARTNER_DISCOUNT);
	}

	/** 연계 학원이 발급한 패스를 JEY 데스크가 썼다 → 발급한 학원의 장부. */
	@ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
	void on(PassRedeemed event) {
		ledger.record(event.passId(), event.partnerId(), PartnerLedgerKind.USED_AT_JEY);
	}

}
