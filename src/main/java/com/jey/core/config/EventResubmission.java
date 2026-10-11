package com.jey.core.config;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 처리에 실패한 도메인 이벤트를 다시 보낸다(ADR-0025).
 *
 * <p>리스너가 실패하면 그 이벤트는 발행 기록에 미완료로 남는다. 남기만 하고 저절로 다시 실행되지는 않는다.
 * 다시 보내는 것이 없으면 DB가 잠깐 끊긴 사이의 이벤트는 처리되지 않은 채로 남는다(감사 로그의 한 줄이 빠진다).
 * 일정 시간이 지나도 끝나지 않은 것을 주기적으로 다시 보낸다. 리스너가 멱등이어야 하는 이유가 이것이다.
 *
 * <p>방금 발행돼 아직 처리 중인 이벤트까지 다시 보내지 않도록, 발행된 지 {@code older-than}이 지난 것만 보낸다.
 * 서버가 내려갔다 올라올 때 남아 있던 것은 시작하면서 다시 보낸다({@code republish-outstanding-events-on-restart}).
 */
@Component
@EnableScheduling
public class EventResubmission {

	private static final Logger log = LoggerFactory.getLogger(EventResubmission.class);

	private final IncompleteEventPublications incompletePublications;

	private final Duration olderThan;

	EventResubmission(IncompleteEventPublications incompletePublications,
			@Value("${jey.events.resubmission.older-than}") Duration olderThan) {
		this.incompletePublications = incompletePublications;
		this.olderThan = olderThan;
	}

	// 앞의 실행이 끝난 뒤부터 간격을 센다. 다시 보내는 일이 오래 걸려도 겹쳐 돌지 않는다.
	@Scheduled(fixedDelayString = "${jey.events.resubmission.interval}",
			initialDelayString = "${jey.events.resubmission.interval}")
	void resubmitStale() {
		resubmitOlderThan(olderThan);
	}

	/**
	 * 발행된 지 이 시간이 지나도록 끝나지 않은 이벤트를 다시 보낸다.
	 */
	public void resubmitOlderThan(Duration age) {
		try {
			incompletePublications.resubmitIncompletePublicationsOlderThan(age);
		}
		catch (RuntimeException ex) {
			// 여기서 던지면 스케줄러가 이 작업을 멈춘다. 남기고 다음 주기에 다시 한다.
			log.error("미완료 이벤트를 다시 보내지 못했다. 다음 주기에 다시 시도한다", ex);
		}
	}

}
