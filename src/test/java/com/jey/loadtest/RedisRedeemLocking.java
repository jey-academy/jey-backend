package com.jey.loadtest;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.jey.core.shared.BusinessException;
import com.jey.dongtanpass.application.DongtanpassErrorCode;
import com.jey.dongtanpass.application.RedeemLocking;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassRepository;
import com.jey.dongtanpass.domain.Redemption;
import com.jey.dongtanpass.domain.RedemptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 비교 실험의 {@code redis} 방식: 트랜잭션 밖에서 패스마다 Redis 락을 잡고, 안에서는 운영 방식(낙관적 락, 패스 먼저 저장)을 그대로 쓴다.
 * 앞단에서 같은 패스의 경합을 걸러 DB까지 가는 요청을 줄이는지를 본다. 정확성은 여전히 DB(버전, 유니크 키)가 지킨다.
 *
 * <ul>
 * <li>잡기: {@code SET key token NX PX ttl}. 이미 있으면 기다리지 않고 바로 "다시 시도"(409)로 거른다.</li>
 * <li>풀기: Lua로 "값이 내 토큰일 때만" 지운다. 일이 만료 시간보다 오래 걸려 남이 다시 잡은 락을 지우지 않는다.</li>
 * <li>잡았는지 알 수 없으면(파이프라인 등으로 응답이 비면) 통과시키지 않는다.</li>
 * <li>풀다가 실패해도(Redis 연결 끊김 등) 원래 결과나 예외를 그대로 돌려준다. 락은 만료 시간이 지나면 풀린다.
 * 이미 커밋한 사용 처리를 해제 실패 때문에 500으로 답하지 않게 한다(측정이 어긋난다).</li>
 * </ul>
 */
public class RedisRedeemLocking implements RedeemLocking {

	private static final Logger log = LoggerFactory.getLogger(RedisRedeemLocking.class);

	static final String KEY_PREFIX = "jey:loadtest:redeem-lock:";

	private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>("""
			if redis.call('get', KEYS[1]) == ARGV[1] then
			  return redis.call('del', KEYS[1])
			end
			return 0
			""", Long.class);

	private final PassRepository passes;

	private final RedemptionRepository redemptions;

	private final StringRedisTemplate redis;

	private final Duration ttl;

	public RedisRedeemLocking(PassRepository passes, RedemptionRepository redemptions, StringRedisTemplate redis,
			Duration ttl) {
		this.passes = passes;
		this.redemptions = redemptions;
		this.redis = redis;
		this.ttl = ttl;
	}

	@Override
	public <T> T guard(long passId, Supplier<T> work) {
		String key = KEY_PREFIX + passId;
		String token = UUID.randomUUID().toString();
		Boolean acquired = redis.opsForValue().setIfAbsent(key, token, ttl);
		if (acquired == null) {
			throw new IllegalStateException("Redis 락을 잡았는지 알 수 없다: passId=" + passId);
		}
		if (!acquired) {
			throw new BusinessException(DongtanpassErrorCode.PASS_CHANGED_CONCURRENTLY);
		}
		try {
			return work.get();
		}
		finally {
			release(key, token, passId);
		}
	}

	private void release(String key, String token, long passId) {
		try {
			Long released = redis.execute(RELEASE, List.of(key), token);
			if (released == null || released == 0L) {
				// 일이 만료 시간보다 오래 걸려 락이 풀렸다(남이 다시 잡았을 수 있다). 만료 시간이 짧은지 봐야 한다.
				log.warn("Redis 락을 풀 때 이미 내 락이 아니었다(만료): passId={}", passId);
			}
		}
		catch (RuntimeException ex) {
			log.error("Redis 락을 풀지 못했다. 만료 시간이 지나면 풀린다: passId={}", passId, ex);
		}
	}

	@Override
	public Optional<Pass> load(long passId) {
		return passes.findById(passId);
	}

	@Override
	public Redemption store(Pass pass, Redemption redemption) {
		passes.saveAndFlush(pass);
		return redemptions.saveAndFlush(redemption);
	}

}
