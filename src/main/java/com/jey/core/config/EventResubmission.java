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
 * 발행된 지 일정 시간이 지나도 끝나지 않은 것을 주기적으로 다시 보낸다. 리스너가 멱등이어야 하는 이유가 이것이다.
 *
 * <p>발행된 지 {@code older-than}이 지난 것만 보낸다. 방금 발행돼 처음 처리되는 중인 이벤트를 건드리지 않기 위해서다.
 * 다만 그 시간이 지난 이벤트는 다시 보낸 리스너가 아직 도는 중이어도 다음 주기에 또 보내질 수 있다.
 * 리스너는 한 주기보다 훨씬 빨리 끝나야 한다.
 *
 * <p>다시 보낼 때 발행 기록에 "다시 보내는 중" 표시가 먼저 붙고, 그 표시가 붙은 것은 다시 보내지 않는다.
 * 표시가 붙은 뒤 리스너가 시작하기 전에 서버가 내려가면 그 이벤트는 표시가 붙은 채로 남는다.
 * 오래된 표시를 실패로 되돌리는 것은 {@code spring.modulith.events.staleness.*} 설정이 한다(발행 시각 기준). 이 설정이 없으면 그런 이벤트는 영영 다시 보내지지 않는다.
 * 미완료 건을 볼 때는 발행 기록의 상태가 아니라 완료 시각이 비어 있는지를 본다. 이 감시가 오래된 건의 상태를 실패로 바꿔 놓기 때문이다.
 */
@Component
@EnableScheduling
public class EventResubmission {

	// 이보다 짧으면 방금 발행돼 처리 중인 이벤트까지 다시 보내게 된다. 단위를 빠뜨린 설정(5 → 5밀리초)을 막는다.
	static final Duration MIN_OLDER_THAN = Duration.ofMinutes(1);

	static final Duration MIN_INTERVAL = Duration.ofSeconds(10);

	private static final Logger log = LoggerFactory.getLogger(EventResubmission.class);

	private final IncompleteEventPublications incompletePublications;

	private final Duration olderThan;

	EventResubmission(IncompleteEventPublications incompletePublications,
			@Value("${jey.events.resubmission.interval}") Duration interval,
			@Value("${jey.events.resubmission.older-than}") Duration olderThan) {
		if (interval.compareTo(MIN_INTERVAL) < 0) {
			throw new IllegalStateException("jey.events.resubmission.interval은 " + MIN_INTERVAL.toSeconds()
					+ "초 이상이어야 한다(단위를 적었는지 확인한다. 예: 1m): " + interval);
		}
		if (olderThan.compareTo(MIN_OLDER_THAN) < 0) {
			throw new IllegalStateException("jey.events.resubmission.older-than은 " + MIN_OLDER_THAN.toMinutes()
					+ "분 이상이어야 한다(단위를 적었는지 확인한다. 예: 5m): " + olderThan);
		}
		this.incompletePublications = incompletePublications;
		this.olderThan = olderThan;
		log.info("미완료 이벤트 재전송: {}마다, 발행된 지 {} 지난 것", interval, olderThan);
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
			// 던져도 스케줄러는 다음 주기에 다시 돈다. 무엇을 하다 실패했는지 알 수 있게 여기서 남긴다.
			// 미완료 목록을 읽지 못했거나 "다시 보내는 중" 표시를 붙이지 못한 경우다.
			// 리스너의 실패와, 저장된 이벤트를 되살리지 못한 것은 여기로 오지 않는다(뒤의 것은 라이브러리가 INFO로만 남긴다).
			log.error("미완료 이벤트를 다시 보내지 못했다", ex);
		}
	}

}
