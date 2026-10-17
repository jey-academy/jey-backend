package com.jey.loadtest;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

/**
 * 부하 시험 중 DB 연결 풀의 활성·대기 수를 잰다. {@code sample} 간격으로 읽어 {@code window} 구간마다 그 구간의 최대값을 로그로 남기고
 * 0부터 다시 센다(시나리오마다 시각으로 구간을 골라 본다). 끌 때는 띄운 뒤 전체의 최대값을 {@code [final]}로 남긴다(컨텍스트가 닫힐 때라
 * 로깅이 아직 살아 있다). 읽다 예외가 나도 다음 표본은 계속 잰다.
 */
class PoolSampler implements DisposableBean {

	private static final Logger log = LoggerFactory.getLogger("loadtest");

	private final HikariDataSource dataSource;

	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "loadtest-pool-sampler");
		thread.setDaemon(true);
		return thread;
	});

	private final AtomicInteger windowActive = new AtomicInteger();

	private final AtomicInteger windowPending = new AtomicInteger();

	private final AtomicInteger totalActive = new AtomicInteger();

	private final AtomicInteger totalPending = new AtomicInteger();

	PoolSampler(HikariDataSource dataSource, Duration sample, Duration window) {
		this.dataSource = dataSource;
		executor.scheduleAtFixedRate(this::sample, sample.toMillis(), sample.toMillis(), TimeUnit.MILLISECONDS);
		executor.scheduleAtFixedRate(this::report, window.toMillis(), window.toMillis(), TimeUnit.MILLISECONDS);
	}

	// 예외를 밖으로 던지면 scheduleAtFixedRate가 그 뒤 실행을 조용히 멈춘다. 잡아서 남기고 계속 잰다.
	private void sample() {
		try {
			HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();
			if (pool == null) {
				return;
			}
			int active = pool.getActiveConnections();
			int pending = pool.getThreadsAwaitingConnection();
			windowActive.accumulateAndGet(active, Math::max);
			windowPending.accumulateAndGet(pending, Math::max);
			totalActive.accumulateAndGet(active, Math::max);
			totalPending.accumulateAndGet(pending, Math::max);
		}
		catch (RuntimeException ex) {
			log.warn("loadtest pool 표본을 읽지 못했다", ex);
		}
	}

	private void report() {
		try {
			int active = windowActive.getAndSet(0);
			int pending = windowPending.getAndSet(0);
			// 한가한 구간은 남기지 않는다(로그를 시나리오 구간으로만 채운다).
			if (active > 0 || pending > 0) {
				log.info("loadtest pool window max active={} pending={} (size {})", active, pending,
						dataSource.getMaximumPoolSize());
			}
		}
		catch (RuntimeException ex) {
			log.warn("loadtest pool 구간 값을 남기지 못했다", ex);
		}
	}

	@Override
	public void destroy() {
		executor.shutdownNow();
		log.info("loadtest pool max active={} pending={} (size {}) [final]", totalActive.get(), totalPending.get(),
				dataSource.getMaximumPoolSize());
	}

}
