package com.jey.core.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import com.jey.core.auth.domain.User;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 로그인 시도 횟수를 Redis에 세서, 한 곳에서 반복하는 비밀번호 대입을 막는다 (ADR-0020).
 *
 * <p>두 가지를 센다. "아이디 + IP"는 한 곳에서 한 계정을 계속 찌르는 시도를, "IP"는 한 곳에서 아이디를 바꿔 가며 찌르는 시도를 막는다.
 * 아이디만으로 세지 않는 것은, 누군가 밖에서 일부러 틀려 학원 PC의 계정을 잠그지 못하게 하기 위해서다.
 *
 * <p>횟수는 비밀번호를 검증하기 <b>전에</b> 올린다. 해시 계산이 느려서 "확인한 뒤 실패하면 증가"로 만들면
 * 동시에 보낸 요청이 모두 확인을 통과한다. 로그인에 성공하면 {@link #loginSucceeded}가 방금 올린 횟수를 되돌린다.
 */
@Component
class LoginAttemptLimiter {

	private static final String KEY_PREFIX = "jey:auth:login-attempts:";

	// 증가와 만료 시간 지정을 한 번에 한다. 따로 하면 그 사이에 연결이 끊겼을 때 만료 시간 없는 키가 남아 영원히 잠긴다.
	// 만료 시간은 처음 만들 때만 정한다. 막힌 뒤에 계속 시도해도 풀리는 시각이 밀리지 않는다.
	// 반환: 올린 뒤의 횟수, 남은 만료 시간(밀리초)
	private static final RedisScript<List> COUNT = new DefaultRedisScript<>("""
			local count = redis.call('INCR', KEYS[1])
			if count == 1 then
				redis.call('PEXPIRE', KEYS[1], ARGV[1])
			end
			return {count, redis.call('PTTL', KEYS[1])}
			""", List.class);

	// 아이디 기준 횟수는 지우고, IP 기준 횟수는 방금 올린 1만 되돌린다.
	// 키가 있을 때만 줄인다. 그사이 만료된 키를 줄이면 만료 시간 없는 -1 키가 생긴다.
	private static final RedisScript<Long> FORGET = new DefaultRedisScript<>("""
			redis.call('DEL', KEYS[1])
			if redis.call('EXISTS', KEYS[2]) == 1 and tonumber(redis.call('GET', KEYS[2])) > 0 then
				redis.call('DECR', KEYS[2])
			end
			return 0
			""", Long.class);

	private final StringRedisTemplate redis;

	private final LoginAttemptProperties properties;

	LoginAttemptLimiter(StringRedisTemplate redis, LoginAttemptProperties properties) {
		this.redis = redis;
		this.properties = properties;
	}

	/**
	 * 시도를 한 번 센다.
	 *
	 * @return 한도를 넘었으면 다시 시도할 수 있을 때까지 남은 시간. 넘지 않았으면 빈 값
	 */
	Optional<Duration> countAttempt(String loginId, String clientIp) {
		Duration byLoginId = exceededFor(loginIdKey(loginId, clientIp), properties.maxFailuresPerLoginIdAndIp());
		Duration byIp = exceededFor(ipKey(clientIp), properties.maxFailuresPerIp());
		if (byLoginId == null && byIp == null) {
			return Optional.empty();
		}
		if (byLoginId == null || byIp == null) {
			return Optional.of((byLoginId != null) ? byLoginId : byIp);
		}
		// 둘 다 넘었으면 둘 다 풀려야 다시 시도할 수 있다.
		return Optional.of((byLoginId.compareTo(byIp) > 0) ? byLoginId : byIp);
	}

	/** 로그인에 성공했을 때 부른다. 이 IP에서 이 아이디로 실패한 횟수를 지운다. */
	void loginSucceeded(String loginId, String clientIp) {
		redis.execute(FORGET, List.of(loginIdKey(loginId, clientIp), ipKey(clientIp)));
	}

	// 한도를 넘었으면 남은 시간, 아니면 null
	private Duration exceededFor(String key, int limit) {
		List<?> result = redis.execute(COUNT, List.of(key), String.valueOf(properties.window().toMillis()));
		long count = ((Number) result.get(0)).longValue();
		long ttlMillis = ((Number) result.get(1)).longValue();
		if (count <= limit) {
			return null;
		}
		// 남은 시간을 읽는 순간 키가 만료됐으면 음수가 온다. 이미 풀렸다는 뜻이므로 가장 짧은 시간으로 답한다.
		return Duration.ofMillis(Math.max(ttlMillis, 1));
	}

	// 아이디 원문은 키에 넣지 않는다. 아이디 칸에 비밀번호를 잘못 넣는 일이 흔하다.
	private static String loginIdKey(String loginId, String clientIp) {
		return KEY_PREFIX + "id:" + hash((loginId != null) ? User.normalizeLoginId(loginId) : "") + ":" + clientIp;
	}

	private static String ipKey(String clientIp) {
		return KEY_PREFIX + "ip:" + clientIp;
	}

	private static String hash(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest, 0, 16);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256을 쓸 수 없다", ex);
		}
	}

}
