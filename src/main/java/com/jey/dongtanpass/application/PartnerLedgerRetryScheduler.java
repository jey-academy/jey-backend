package com.jey.dongtanpass.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 다시 시도할 때가 된 연계 학원 장부 반영을 집는다({@code jey.dongtanpass.partner-ledger.retry-interval}, 기본 1분).
 * 앞의 실행이 끝난 뒤부터 간격을 센다(겹쳐 돌지 않는다). 서버가 둘이어도 한 줄은 한 쪽만 맡는다(행 잠금).
 */
@Component
class PartnerLedgerRetryScheduler {

	private static final Logger log = LoggerFactory.getLogger(PartnerLedgerRetryScheduler.class);

	private final PartnerLedgerService ledger;

	PartnerLedgerRetryScheduler(PartnerLedgerService ledger) {
		this.ledger = ledger;
	}

	@Scheduled(fixedDelayString = "${jey.dongtanpass.partner-ledger.retry-interval:1m}",
			initialDelayString = "${jey.dongtanpass.partner-ledger.retry-interval:1m}")
	void retryDue() {
		try {
			ledger.retryDue();
		} catch (RuntimeException e) {
			// 던져도 다음 주기에 다시 돈다. 다시 시도할 줄을 읽지 못한 경우다(줄마다의 실패는 서비스가 남긴다).
			log.error("연계 학원 장부 반영의 다시 시도를 시작하지 못했다", e);
		}
	}

}
