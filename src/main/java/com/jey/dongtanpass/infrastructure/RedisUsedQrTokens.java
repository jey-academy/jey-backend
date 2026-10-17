package com.jey.dongtanpass.infrastructure;

import java.time.Duration;
import java.time.Instant;

import com.jey.dongtanpass.application.UsedQrTokens;
import com.jey.dongtanpass.application.VerifiedQr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 쓴 QR 토큰의 ID를 Redis에 남은 수명만큼 적는다. 키는 {@code jey:dongtanpass:qr-used:{토큰 ID}}다.
 */
@Component
class RedisUsedQrTokens implements UsedQrTokens {

	private static final Logger log = LoggerFactory.getLogger(RedisUsedQrTokens.class);

	private static final String KEY_PREFIX = "jey:dongtanpass:qr-used:";

	// 만료 직전에 적은 토큰도 잠깐은 기억한다. 수명 0을 넘기면 만료 없이 적혀 영원히 남는다.
	private static final Duration MIN_TTL = Duration.ofSeconds(1);

	private final StringRedisTemplate redis;

	RedisUsedQrTokens(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public boolean markUsed(VerifiedQr qr, Instant now) {
		Boolean first;
		try {
			first = redis.opsForValue().setIfAbsent(KEY_PREFIX + qr.tokenId(), "1", ttl(qr.expiresAt(), now));
		}
		catch (RuntimeException ex) {
			// 시간이 다 돼 끊긴 경우 Redis에는 적혔을 수도 있다. 같은 QR이 다시 오면 재사용으로 보일 수 있어 패스를 남긴다(토큰 ID는 남기지 않는다).
			log.error("쓴 QR 토큰을 적지 못했다. 적혔는지는 알 수 없다: passId={}", qr.passId());
			throw ex;
		}
		// 파이프라인·트랜잭션 안에서는 null이 온다. 적었는지 모르는 채로 통과시키지 않는다.
		if (first == null) {
			throw new IllegalStateException("쓴 QR 토큰을 적었는지 알 수 없다");
		}
		return first;
	}

	// 남은 수명을 초로 올린다. 초 아래를 버리면 만료 직전의 몇백 밀리초 동안 같은 토큰이 다시 통과한다.
	private static Duration ttl(Instant expiresAt, Instant now) {
		Duration remaining = Duration.between(now, expiresAt);
		long seconds = remaining.getSeconds() + (remaining.getNano() > 0 ? 1 : 0);
		Duration rounded = Duration.ofSeconds(seconds);
		return rounded.compareTo(MIN_TTL) < 0 ? MIN_TTL : rounded;
	}

}
