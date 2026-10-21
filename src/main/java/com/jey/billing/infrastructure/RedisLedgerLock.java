package com.jey.billing.infrastructure;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.jey.billing.application.LedgerLock;
import com.jey.billing.application.LedgerLockUnavailable;

/** Redis 키 {@code jey:billing:ledger-lock:{스프레드시트 ID}}로 거는 잠금. 값은 쥔 쪽의 표시이고 {@link #TTL} 뒤에 저절로 풀린다. */
@Component
class RedisLedgerLock implements LedgerLock {

	private static final Logger log = LoggerFactory.getLogger(RedisLedgerLock.class);

	static final String KEY_PREFIX = "jey:billing:ledger-lock:";

	/**
	 * 한 번의 시도는 구글 요청 둘(읽기, 쓰기)이고 각각 연결·응답 제한이 있다({@code LedgerProperties}의 상한 5초·15초 — 최대 2 × 20초).
	 * 그보다 길게 잡는다. 결과를 모르는 쓰기 뒤에는 이 시간 동안 잠금을 남겨 늦게 반영되는 쓰기를 기다린다.
	 */
	static final Duration TTL = Duration.ofSeconds(45);

	private static final Duration POLL = Duration.ofMillis(100);

	// 내가 넣은 값일 때만 지운다. 시간이 지나 다른 쪽이 잡은 잠금을 지우지 않는다.
	private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>(
			"if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
			Long.class);

	private final StringRedisTemplate redis;

	RedisLedgerLock(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public Optional<Held> tryAcquire(String spreadsheetId, Duration wait) {
		String key = KEY_PREFIX + spreadsheetId;
		String token = UUID.randomUUID().toString();
		long deadline = System.nanoTime() + wait.toNanos();
		while (true) {
			if (acquire(key, token)) {
				return Optional.of(new RedisHeld(key, token));
			}
			if (System.nanoTime() >= deadline) {
				return Optional.empty();
			}
			try {
				Thread.sleep(POLL);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return Optional.empty();
			}
		}
	}

	private boolean acquire(String key, String token) {
		try {
			return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, token, TTL));
		} catch (DataAccessException e) {
			throw new LedgerLockUnavailable("장부 잠금 저장소(Redis)에 닿지 못했다", e);
		}
	}

	private final class RedisHeld implements Held {

		private final String key;

		private final String token;

		private boolean keep;

		RedisHeld(String key, String token) {
			this.key = key;
			this.token = token;
		}

		@Override
		public void keepUntilExpiry() {
			this.keep = true;
		}

		@Override
		public void close() {
			if (keep) {
				return;
			}
			try {
				redis.execute(RELEASE, List.of(key), token);
			} catch (DataAccessException e) {
				// 결과(쓴 것)는 그대로다. 잠금은 TTL 뒤에 저절로 풀린다.
				log.warn("장부 잠금을 풀지 못했다({} 뒤 저절로 풀린다): {}", TTL, key, e);
			}
		}

	}

}
