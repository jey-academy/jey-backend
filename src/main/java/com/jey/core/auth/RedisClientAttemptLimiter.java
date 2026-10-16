package com.jey.core.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import com.jey.core.auth.api.ClientAttemptLimiter;
import com.jey.core.auth.api.RateLimit;
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
 * 로그인 없는 경로의 틀린 시도를 Redis에 센다. 고정 구간이다: 처음 센 때부터 구간이 지나면 그 기준의 횟수가 지워진다.
 *
 * <p>기준 하나에 해시 키 하나를 쓴다. 필드의 뜻은 {@link RedisAccountRateLimiter}와 같다: 횟수({@code count}),
 * 구간의 표식({@code window}), 막힌 것을 알렸는지({@code noticed}).
 */
@Component
class RedisClientAttemptLimiter implements ClientAttemptLimiter {

	private static final Logger log = LoggerFactory.getLogger(RedisClientAttemptLimiter.class);

	private static final String KEY_PREFIX = "jey:auth:client-limit:";

	// 막힌 것을 알리는 일(감사 로그 쓰기)에 주는 시간. 알리던 서버가 죽으면 이 시간 뒤에 다음 막힌 요청이 다시 알린다.
	private static final Duration NOTICE_LEASE = Duration.ofSeconds(30);

	// 두 기준의 확인과 증가를 한 번에 한다. 따로 하면 동시에 보낸 요청이 한도를 넘기고, 한 기준만 올라간 채 끝날 수도 있다.
	// 하나라도 한도에 닿아 있으면 어느 쪽도 세지 않는다. 둘 다 닿아 있으면 남은 시간이 긴 쪽으로 답하고, 알리는 일도 그 기준에서 가린다.
	// 만료 시간은 처음 만들 때만 정한다. 만료 시간이 없는 키는 영원히 잠그므로, 밖에서 생겼다면(수동 조작, 복원) 여기서 고친다.
	// KEYS: 대상 + 주소 키, 주소 키
	// ARGV: 대상 + 주소의 window(밀리초)와 한도, 주소의 window(밀리초)와 한도, 새 구간에 붙일 표식, 알리는 일에 주는 시간(밀리초)
	// 반환: 막은 기준(0 통과, 1 대상 + 주소, 2 주소), 남은 시간(밀리초), 처음 막힌 것인지, 만료 시간을 고친 키의 수, 두 구간의 표식
	private static final RedisScript<List> ACQUIRE = new DefaultRedisScript<>("""
			local repaired = 0
			local counts = {}
			local ttls = {}
			for i = 1, 2 do
				counts[i] = tonumber(redis.call('HGET', KEYS[i], 'count') or '0')
				ttls[i] = redis.call('PTTL', KEYS[i])
				if ttls[i] == -1 then
					redis.call('PEXPIRE', KEYS[i], tonumber(ARGV[i * 2 - 1]))
					ttls[i] = tonumber(ARGV[i * 2 - 1])
					repaired = repaired + 1
				end
			end
			local blocked = 0
			for i = 1, 2 do
				if counts[i] >= tonumber(ARGV[i * 2]) and (blocked == 0 or ttls[i] > ttls[blocked]) then
					blocked = i
				end
			end
			if blocked > 0 then
				local ttl = ttls[blocked]
				if ttl < 1 then
					ttl = 1
				end
				local time = redis.call('TIME')
				local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
				local noticed = redis.call('HGET', KEYS[blocked], 'noticed')
				local first = 0
				if not noticed or (noticed ~= 'done' and tonumber(noticed) <= now) then
					redis.call('HSET', KEYS[blocked], 'noticed', string.format('%.0f', now + tonumber(ARGV[6])))
					first = 1
				end
				return {blocked, ttl, first, repaired, '', ''}
			end
			local ids = {}
			for i = 1, 2 do
				ids[i] = redis.call('HGET', KEYS[i], 'window')
				if not ids[i] then
					ids[i] = ARGV[5]
					redis.call('HSET', KEYS[i], 'window', ids[i])
				end
				redis.call('HINCRBY', KEYS[i], 'count', 1)
				if redis.call('PTTL', KEYS[i]) == -1 then
					redis.call('PEXPIRE', KEYS[i], tonumber(ARGV[i * 2 - 1]))
				end
			end
			return {0, 0, 0, repaired, ids[1], ids[2]}
			""", List.class);

	// 기준마다, 같은 구간에서 센 것일 때만 줄인다. 구간이 바뀐 뒤에 줄이면 새 구간에서 센 것이 지워진다.
	// 키가 없으면 아무것도 하지 않는다. 없는 키를 줄이면 만료 시간 없는 키가 생긴다.
	// KEYS: 대상 + 주소 키, 주소 키 / ARGV: 각 키에서 센 구간의 표식
	private static final RedisScript<Long> REFUND = new DefaultRedisScript<>("""
			for i = 1, 2 do
				if redis.call('HGET', KEYS[i], 'window') == ARGV[i]
						and tonumber(redis.call('HGET', KEYS[i], 'count') or '0') > 0 then
					redis.call('HINCRBY', KEYS[i], 'count', -1)
				end
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

	RedisClientAttemptLimiter(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public Attempt acquire(RateLimit perSubjectAndClient, RateLimit perClient, String subject, String clientAddress,
			ErrorCode limitedCode, Consumer<RateLimitedException> onFirstBlock) {
		Objects.requireNonNull(perSubjectAndClient, "perSubjectAndClient");
		Objects.requireNonNull(perClient, "perClient");
		Objects.requireNonNull(onFirstBlock, "onFirstBlock");
		// 세기 전에 본다. 막힌 뒤에야 드러나면 "알리는 중" 표시만 남고 아무것도 알리지 못한다.
		if (limitedCode.status() != HttpStatus.TOO_MANY_REQUESTS) {
			throw new IllegalArgumentException("횟수 한도에 쓰는 에러 코드의 상태는 429여야 한다: " + limitedCode.code());
		}
		if (perSubjectAndClient.name().equals(perClient.name())) {
			throw new IllegalArgumentException("두 기준의 한도 이름이 같으면 한 키를 함께 센다: " + perClient.name());
		}
		if (subject == null || subject.isBlank()) {
			throw new IllegalArgumentException("대상은 비울 수 없다");
		}
		String address = ClientAddresses.keyPart(clientAddress);
		String subjectKey = KEY_PREFIX + perSubjectAndClient.name() + ":" + digest(subject) + ":" + address;
		String clientKey = KEY_PREFIX + perClient.name() + ":" + address;
		List<?> result;
		try {
			result = redis.execute(ACQUIRE, List.of(subjectKey, clientKey),
					String.valueOf(perSubjectAndClient.window().toMillis()), String.valueOf(perSubjectAndClient.max()),
					String.valueOf(perClient.window().toMillis()), String.valueOf(perClient.max()),
					UUID.randomUUID().toString(), String.valueOf(NOTICE_LEASE.toMillis()));
		}
		catch (RuntimeException ex) {
			// 응답만 잃었다면 횟수는 이미 올라갔을 수 있다. 이 주소가 나중에 까닭 없이 막혔다면 이 로그를 본다.
			log.error("틀린 시도를 세다 실패했다. 셌는지는 알 수 없다: limit={} address={}", perSubjectAndClient.name(), address);
			throw ex;
		}
		if (result == null || result.size() != 6 || !result.subList(0, 4).stream().allMatch(Number.class::isInstance)
				|| !(result.get(4) instanceof String subjectWindow) || !(result.get(5) instanceof String clientWindow)) {
			throw new IllegalStateException("틀린 시도 스크립트의 결과가 예상과 다르다: " + result);
		}
		if (((Number) result.get(3)).intValue() > 0) {
			// 키에는 한도 이름, 대상의 해시, 주소만 있다.
			log.error("만료 시간이 없는 틀린 시도 키를 고쳤다. 누가 Redis를 직접 건드렸는지 확인할 것: {} / {}", subjectKey, clientKey);
		}
		int blockedBy = ((Number) result.get(0)).intValue();
		if (blockedBy == 0) {
			return new CountedAttempt(subjectKey, subjectWindow, clientKey, clientWindow);
		}
		String blockedKey = switch (blockedBy) {
			case 1 -> subjectKey;
			case 2 -> clientKey;
			default -> throw new IllegalStateException("틀린 시도 스크립트의 결과가 예상과 다르다: " + result);
		};
		RateLimitedException denial = new RateLimitedException(limitedCode,
				Duration.ofMillis(((Number) result.get(1)).longValue()));
		if (((Number) result.get(2)).intValue() == 1) {
			notifyFirstBlock(blockedKey, denial, onFirstBlock);
		}
		throw denial;
	}

	private void notifyFirstBlock(String key, RateLimitedException denial, Consumer<RateLimitedException> onFirstBlock) {
		try {
			onFirstBlock.accept(denial);
		}
		catch (RuntimeException failure) {
			// 표시를 지워야 다음에 막힌 요청이 다시 알린다. 지우지 못해도 알리는 일에 준 시간이 지나면 다시 알린다.
			log.error("한도에 막힌 사실을 알리지 못했다. 다음에 막힌 요청이 다시 알린다: {}", key);
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
			log.error("한도에 막힌 사실을 알린 뒤 표시를 남기지 못했다. 같은 구간에 한 번 더 알릴 수 있다: {}", key, ex);
		}
	}

	@Override
	public void refund(Attempt attempt) {
		if (!(attempt instanceof CountedAttempt counted)) {
			throw new IllegalArgumentException("이 장치가 센 시도가 아니다");
		}
		redis.execute(REFUND, List.of(counted.subjectKey(), counted.clientKey()), counted.subjectWindow(),
				counted.clientWindow());
	}

	/**
	 * 센 한 번. 두 기준의 키와 각 키에서 센 구간의 표식을 든다. 이 장치만 만든다.
	 */
	private record CountedAttempt(String subjectKey, String subjectWindow, String clientKey, String clientWindow)
			implements Attempt {

		// 키에 접속 주소와 대상의 해시가 있다. 로그에 찍혀도 남지 않게 한다.
		@Override
		public String toString() {
			return "CountedAttempt[***]";
		}

	}

	// 대상은 이미 대조값이지만 길이를 줄이고, 원문을 넘긴 실수가 있어도 키에 그대로 남지 않게 한다.
	private static String digest(String subject) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(subject.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash, 0, 16);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256을 쓸 수 없다", ex);
		}
	}

}
