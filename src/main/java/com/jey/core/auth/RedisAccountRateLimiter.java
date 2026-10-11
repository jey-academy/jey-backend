package com.jey.core.auth;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import com.jey.core.auth.api.AccountRateLimiter;
import com.jey.core.auth.api.RateLimit;
import com.jey.core.shared.Actor;
import com.jey.core.shared.ErrorCode;
import com.jey.core.shared.RateLimitedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 계정별 횟수를 Redis에 센다. 고정 구간이다: 처음 센 때부터 구간이 지나면 횟수가 지워진다.
 *
 * <p>한도 하나와 계정 하나에 해시 키 하나를 쓴다. 횟수({@code count}), 구간의 표식({@code window}),
 * 막힌 것을 알렸는지({@code noticed})가 한 키에 있어서 같은 때에 사라진다.
 *
 * <p>로그인 시도 제한({@link LoginAttemptLimiter})과 같은 방식이지만 따로 둔다.
 * 그쪽은 로그인하기 전이라 계정이 아니라 "아이디 + IP"와 "IP"로 센다.
 */
@Component
class RedisAccountRateLimiter implements AccountRateLimiter {

	private static final Logger log = LoggerFactory.getLogger(RedisAccountRateLimiter.class);

	private static final String KEY_PREFIX = "jey:auth:account-limit:";

	// 막힌 것을 알리는 일(감사 로그 쓰기)에 주는 시간. 알리던 서버가 죽으면 이 시간 뒤에 다음 막힌 요청이 다시 알린다.
	private static final Duration NOTICE_LEASE = Duration.ofSeconds(30);

	// 확인과 증가를 한 번에 한다. 따로 하면 동시에 보낸 요청이 한도를 넘긴다.
	// 만료 시간은 처음 만들 때만 정한다. 만료 시간이 없는 키는 영원히 잠그므로, 밖에서 생겼다면(수동 조작, 복원) 여기서 고친다.
	// 구간의 표식은 처음 셀 때 정한다. 되돌릴 때 같은 구간인지 가리는 데 쓴다.
	// 막혔을 때: 알린 적이 없거나, 알리던 쪽이 끝내지 못한 채 시간이 지났으면 "지금 알리는 중"으로 표시하고 처음 막힌 것으로 답한다.
	// KEYS: 횟수 키 / ARGV: window(밀리초), 한도, 새 구간에 붙일 표식, 알리는 일에 주는 시간(밀리초)
	// 반환: 막혔는지, 남은 시간(밀리초), 처음 막힌 것인지, 만료 시간을 고쳤는지, 구간의 표식
	private static final RedisScript<List> ACQUIRE = new DefaultRedisScript<>("""
			local window = tonumber(ARGV[1])
			local repaired = 0
			local count = tonumber(redis.call('HGET', KEYS[1], 'count') or '0')
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
				local time = redis.call('TIME')
				local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
				local noticed = redis.call('HGET', KEYS[1], 'noticed')
				local first = 0
				if not noticed or (noticed ~= 'done' and tonumber(noticed) <= now) then
					redis.call('HSET', KEYS[1], 'noticed', string.format('%.0f', now + tonumber(ARGV[4])))
					first = 1
				end
				return {1, ttl, first, repaired, ''}
			end
			local id = redis.call('HGET', KEYS[1], 'window')
			if not id then
				id = ARGV[3]
				redis.call('HSET', KEYS[1], 'window', id)
			end
			redis.call('HINCRBY', KEYS[1], 'count', 1)
			if redis.call('PTTL', KEYS[1]) == -1 then
				redis.call('PEXPIRE', KEYS[1], window)
			end
			return {0, 0, 0, repaired, id}
			""", List.class);

	// 같은 구간에서 센 것일 때만 줄인다. 구간이 바뀐 뒤에 줄이면 새 구간에서 센 것이 지워진다.
	// 키가 없으면 아무것도 하지 않는다. 없는 키를 줄이면 만료 시간 없는 키가 생긴다.
	// KEYS: 횟수 키 / ARGV: 센 구간의 표식
	private static final RedisScript<Long> REFUND = new DefaultRedisScript<>("""
			if redis.call('HGET', KEYS[1], 'window') == ARGV[1]
					and tonumber(redis.call('HGET', KEYS[1], 'count') or '0') > 0 then
				redis.call('HINCRBY', KEYS[1], 'count', -1)
			end
			return 0
			""", Long.class);

	// 알리는 일이 끝났다. 이 구간에서는 더 알리지 않는다. 그사이 구간이 끝나 키가 없으면 만들지 않는다.
	private static final RedisScript<Long> CONFIRM_NOTICE = new DefaultRedisScript<>("""
			if redis.call('EXISTS', KEYS[1]) == 1 then
				redis.call('HSET', KEYS[1], 'noticed', 'done')
			end
			return 0
			""", Long.class);

	// 알리지 못했다. 다음에 막힌 요청이 바로 다시 알리게 한다.
	private static final RedisScript<Long> FORGET_NOTICE = new DefaultRedisScript<>("""
			redis.call('HDEL', KEYS[1], 'noticed')
			return 0
			""", Long.class);

	private final StringRedisTemplate redis;

	RedisAccountRateLimiter(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public Permit acquire(RateLimit limit, Actor.User account, ErrorCode limitedCode,
			Consumer<RateLimitedException> onFirstBlock) {
		Objects.requireNonNull(limit, "limit");
		Objects.requireNonNull(account, "account");
		Objects.requireNonNull(onFirstBlock, "onFirstBlock");
		// 세기 전에 본다. 막힌 뒤에야 드러나면 "알리는 중" 표시만 남고 아무것도 알리지 못한다.
		if (limitedCode.status() != HttpStatus.TOO_MANY_REQUESTS) {
			throw new IllegalArgumentException("횟수 한도에 쓰는 에러 코드의 상태는 429여야 한다: " + limitedCode.code());
		}
		String key = key(limit, account);
		List<?> result;
		try {
			result = redis.execute(ACQUIRE, List.of(key), String.valueOf(limit.window().toMillis()),
					String.valueOf(limit.max()), UUID.randomUUID().toString(),
					String.valueOf(NOTICE_LEASE.toMillis()));
		}
		catch (RuntimeException ex) {
			// 응답만 잃었다면 횟수는 이미 올라갔을 수 있다. 이 계정이 나중에 까닭 없이 막혔다면 이 로그를 본다.
			log.error("횟수를 세다 실패했다. 셌는지는 알 수 없다: limit={} accountId={}", limit.name(), account.accountId());
			throw ex;
		}
		if (result == null || result.size() != 5 || !result.subList(0, 4).stream().allMatch(Number.class::isInstance)
				|| !(result.get(4) instanceof String window)) {
			throw new IllegalStateException("횟수 제한 스크립트의 결과가 예상과 다르다: " + result);
		}
		if (((Number) result.get(3)).intValue() > 0) {
			log.error("만료 시간이 없는 횟수 제한 키를 고쳤다. 누가 Redis를 직접 건드렸는지 확인할 것: {}", key);
		}
		if (((Number) result.get(0)).intValue() == 0) {
			return new Permit(limit, account, window);
		}
		RateLimitedException denial = new RateLimitedException(limitedCode,
				Duration.ofMillis(((Number) result.get(1)).longValue()));
		if (((Number) result.get(2)).intValue() == 1) {
			notifyFirstBlock(limit, account, key, denial, onFirstBlock);
		}
		throw denial;
	}

	private void notifyFirstBlock(RateLimit limit, Actor.User account, String key, RateLimitedException denial,
			Consumer<RateLimitedException> onFirstBlock) {
		try {
			onFirstBlock.accept(denial);
		}
		catch (RuntimeException failure) {
			// 표시를 지워야 다음에 막힌 요청이 다시 알린다. 지우지 못해도 알리는 일에 준 시간이 지나면 다시 알린다.
			log.error("한도에 막힌 사실을 알리지 못했다. 다음에 막힌 요청이 다시 알린다: limit={} accountId={}", limit.name(),
					account.accountId());
			try {
				redis.execute(FORGET_NOTICE, List.of(key));
			}
			catch (RuntimeException forgetFailure) {
				failure.addSuppressed(forgetFailure);
			}
			throw failure;
		}
		try {
			redis.execute(CONFIRM_NOTICE, List.of(key));
		}
		catch (RuntimeException ex) {
			// 알리는 일은 끝났다. 요청의 결과(막힘)를 바꾸지 않는다.
			log.error("한도에 막힌 사실을 알린 뒤 표시를 남기지 못했다. 같은 구간에 한 번 더 알릴 수 있다: limit={} accountId={}",
					limit.name(), account.accountId(), ex);
		}
	}

	@Override
	public void refund(Permit permit) {
		redis.execute(REFUND, List.of(key(permit.limit(), permit.account())), permit.window());
	}

	private static String key(RateLimit limit, Actor.User account) {
		return KEY_PREFIX + limit.name() + ":" + account.accountId();
	}

}
