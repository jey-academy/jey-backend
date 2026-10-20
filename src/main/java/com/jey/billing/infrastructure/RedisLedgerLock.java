package com.jey.billing.infrastructure;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.jey.billing.application.LedgerLock;

/** Redis 키 {@code jey:billing:ledger-lock:{스프레드시트 ID}}로 거는 잠금. 값은 쥔 쪽의 표시이고 {@link #TTL} 뒤에 저절로 풀린다. */
@Component
class RedisLedgerLock implements LedgerLock {

	static final String KEY_PREFIX = "jey:billing:ledger-lock:";

	/** 한 번의 시도는 구글 요청 둘(읽기, 쓰기)이고 각각 연결·응답 제한이 있다(최대 2 × (5초 + 15초)보다 길게). */
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
			if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, token, TTL))) {
				return Optional.of(() -> redis.execute(RELEASE, List.of(key), token));
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

}
