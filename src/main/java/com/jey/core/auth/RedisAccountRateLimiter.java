package com.jey.core.auth;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.jey.core.auth.api.AccountRateLimiter;
import com.jey.core.auth.api.RateLimit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 계정별 횟수를 Redis에 센다. 고정 구간이다: 처음 센 때부터 구간이 지나면 횟수가 지워진다.
 *
 * <p>로그인 시도 제한({@link LoginAttemptLimiter})과 같은 방식이지만 따로 둔다.
 * 그쪽은 로그인하기 전이라 계정이 아니라 "아이디 + IP"와 "IP"로 센다.
 */
@Component
class RedisAccountRateLimiter implements AccountRateLimiter {

	private static final Logger log = LoggerFactory.getLogger(RedisAccountRateLimiter.class);

	private static final String KEY_PREFIX = "jey:auth:account-limit:";

	// 확인과 증가를 한 번에 한다. 따로 하면 동시에 보낸 요청이 한도를 넘긴다.
	// 만료 시간은 처음 만들 때만 정한다. 만료 시간이 없는 키는 영원히 잠그므로, 밖에서 생겼다면(수동 조작, 복원) 여기서 고친다.
	// 막혔을 때는 "막힌 것을 알렸다" 표시를 횟수와 같은 때에 사라지게 남긴다. 표시가 새로 생겼으면 이 구간에서 처음 막힌 것이다.
	// KEYS: 횟수 키, 알림 표시 키 / ARGV: window(밀리초), 한도
	// 반환: 막혔는지, 남은 시간(밀리초), 처음 막힌 것인지, 만료 시간을 고쳤는지
	private static final RedisScript<List> ACQUIRE = new DefaultRedisScript<>("""
			local window = tonumber(ARGV[1])
			local repaired = 0
			local count = tonumber(redis.call('GET', KEYS[1]) or '0')
			local ttl = redis.call('PTTL', KEYS[1])
			if ttl == -1 then
				redis.call('PEXPIRE', KEYS[1], window)
				ttl = window
				repaired = 1
			end
			if count >= tonumber(ARGV[2]) then
				if ttl < 1 then
					ttl = 1
				end
				local first = 0
				if redis.call('SET', KEYS[2], '1', 'NX', 'PX', ttl) then
					first = 1
				end
				return {1, ttl, first, repaired}
			end
			if redis.call('INCR', KEYS[1]) == 1 then
				redis.call('PEXPIRE', KEYS[1], window)
			end
			return {0, 0, 0, repaired}
			""", List.class);

	// 키가 있을 때만 줄인다. 그사이 만료된 키를 줄이면 만료 시간 없는 -1 키가 생긴다.
	private static final RedisScript<Long> REFUND = new DefaultRedisScript<>("""
			if redis.call('EXISTS', KEYS[1]) == 1 and tonumber(redis.call('GET', KEYS[1])) > 0 then
				redis.call('DECR', KEYS[1])
			end
			return 0
			""", Long.class);

	private final StringRedisTemplate redis;

	RedisAccountRateLimiter(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public Optional<Blocked> tryAcquire(RateLimit limit, long accountId) {
		String countKey = countKey(limit, accountId);
		List<?> result = redis.execute(ACQUIRE, List.of(countKey, noticeKey(limit, accountId)),
				String.valueOf(limit.window().toMillis()), String.valueOf(limit.max()));
		if (result == null || result.size() != 4 || !result.stream().allMatch(Number.class::isInstance)) {
			throw new IllegalStateException("횟수 제한 스크립트의 결과가 예상과 다르다: " + result);
		}
		if (((Number) result.get(3)).intValue() > 0) {
			log.error("만료 시간이 없는 횟수 제한 키를 고쳤다. 누가 Redis를 직접 건드렸는지 확인할 것: {}", countKey);
		}
		if (((Number) result.get(0)).intValue() == 0) {
			return Optional.empty();
		}
		return Optional.of(new Blocked(Duration.ofMillis(((Number) result.get(1)).longValue()),
				((Number) result.get(2)).intValue() == 1));
	}

	@Override
	public void refund(RateLimit limit, long accountId) {
		redis.execute(REFUND, List.of(countKey(limit, accountId)));
	}

	@Override
	public void forgetBlockNotice(RateLimit limit, long accountId) {
		redis.delete(noticeKey(limit, accountId));
	}

	private static String noticeKey(RateLimit limit, long accountId) {
		return countKey(limit, accountId) + ":noted";
	}

	private static String countKey(RateLimit limit, long accountId) {
		return KEY_PREFIX + limit.name() + ":" + accountId;
	}

}
