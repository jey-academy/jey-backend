package com.jey.core.auth;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.jey.core.auth.domain.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * <p>이미 막힌 시도는 세지 않는다. 세면 잠긴 계정으로 계속 시도하는 한 사람 때문에 같은 IP의 모두가 막히고,
 * 막힌 IP가 아이디를 바꿔 가며 키를 끝없이 만들 수 있다.
 */
@Component
class LoginAttemptLimiter {

	private static final Logger log = LoggerFactory.getLogger(LoginAttemptLimiter.class);

	private static final String KEY_PREFIX = "jey:auth:login-attempts:";

	private static final String HMAC_ALGORITHM = "HmacSHA256";

	private static final int NOT_BLOCKED = 0;

	private static final int BLOCKED_BY_LOGIN_ID_AND_IP = 1;

	private static final int BLOCKED_BY_IP = 2;

	// 두 횟수의 확인과 증가를 한 번에 한다. 따로 하면 동시에 보낸 요청이 한도를 넘기고, 하나만 올라간 채 끝날 수도 있다.
	// 만료 시간은 처음 만들 때만 정한다. 고정 구간이라 첫 실패부터 window가 지나면 풀린다.
	// 만료 시간이 없는 키는 영원히 잠긴다. 이 스크립트는 그런 키를 만들지 않지만, 밖에서 생겼다면(수동 조작, 복원) 여기서 고친다.
	// KEYS: 아이디 + IP 키, IP 키 / ARGV: window(밀리초), 아이디 + IP 한도, IP 한도
	// 반환: 막은 기준(0이면 통과), 남은 시간(밀리초), 만료 시간을 고친 키의 수
	private static final RedisScript<List> COUNT = new DefaultRedisScript<>("""
			local window = tonumber(ARGV[1])
			local repaired = 0
			local function state(key)
				local count = tonumber(redis.call('GET', key) or '0')
				local ttl = redis.call('PTTL', key)
				if ttl == -1 then
					redis.call('PEXPIRE', key, window)
					ttl = window
					repaired = repaired + 1
				end
				return count, ttl
			end
			local idCount, idTtl = state(KEYS[1])
			local ipCount, ipTtl = state(KEYS[2])
			local idBlocked = idCount >= tonumber(ARGV[2])
			local ipBlocked = ipCount >= tonumber(ARGV[3])
			if idBlocked and ipBlocked then
				if idTtl >= ipTtl then
					return {1, idTtl, repaired}
				end
				return {2, ipTtl, repaired}
			end
			if idBlocked then
				return {1, idTtl, repaired}
			end
			if ipBlocked then
				return {2, ipTtl, repaired}
			end
			for i = 1, 2 do
				if redis.call('INCR', KEYS[i]) == 1 then
					redis.call('PEXPIRE', KEYS[i], window)
				end
			end
			return {0, 0, repaired}
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

	private final SecretKeySpec hmacKey;

	LoginAttemptLimiter(StringRedisTemplate redis, LoginAttemptProperties properties) {
		this.redis = redis;
		this.properties = properties;
		this.hmacKey = new SecretKeySpec(properties.hmacKey().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
	}

	/**
	 * 시도를 한 번 센다. 이미 한도에 닿아 있으면 세지 않고 막는다.
	 *
	 * @return 막혔으면 어느 기준에 걸렸는지와 다시 시도할 수 있을 때까지 남은 시간. 통과했으면 빈 값
	 */
	Optional<Blocked> countAttempt(String loginId, String clientIp) {
		String ip = ipKeyPart(clientIp);
		String loginIdKey = loginIdKey(loginId, ip);
		String ipKey = ipKey(ip);
		List<?> result = redis.execute(COUNT, List.of(loginIdKey, ipKey),
				String.valueOf(properties.window().toMillis()),
				String.valueOf(properties.maxFailuresPerLoginIdAndIp()),
				String.valueOf(properties.maxFailuresPerIp()));
		if (result == null || result.size() != 3 || !result.stream().allMatch(Number.class::isInstance)) {
			throw new IllegalStateException("로그인 시도 스크립트의 결과가 예상과 다르다: " + result);
		}
		int blockedBy = ((Number) result.get(0)).intValue();
		long retryAfterMillis = ((Number) result.get(1)).longValue();
		if (((Number) result.get(2)).intValue() > 0) {
			// 키에는 아이디의 HMAC과 IP만 있다.
			log.error("만료 시간이 없는 로그인 시도 키를 고쳤다. 누가 Redis를 직접 건드렸는지 확인할 것: {} / {}", loginIdKey, ipKey);
		}
		return switch (blockedBy) {
			case NOT_BLOCKED -> Optional.empty();
			case BLOCKED_BY_LOGIN_ID_AND_IP ->
				Optional.of(new Blocked(Scope.LOGIN_ID_AND_IP, Duration.ofMillis(retryAfterMillis)));
			case BLOCKED_BY_IP -> Optional.of(new Blocked(Scope.IP, Duration.ofMillis(retryAfterMillis)));
			default -> throw new IllegalStateException("로그인 시도 스크립트의 결과가 예상과 다르다: " + result);
		};
	}

	/** 로그인에 성공했을 때 부른다. 이 IP에서 이 아이디로 실패한 횟수를 지운다. */
	void loginSucceeded(String loginId, String clientIp) {
		String ip = ipKeyPart(clientIp);
		redis.execute(FORGET, List.of(loginIdKey(loginId, ip), ipKey(ip)));
	}

	// 아이디 원문은 키에 넣지 않는다. 아이디 칸에 비밀번호를 잘못 넣는 일이 흔하다.
	private String loginIdKey(String loginId, String ip) {
		return KEY_PREFIX + "id:" + hmac((loginId != null) ? User.normalizeLoginId(loginId) : "") + ":" + ip;
	}

	private static String ipKey(String ip) {
		return KEY_PREFIX + "ip:" + ip;
	}

	/**
	 * 키에 넣을 주소. IPv6는 앞 64비트(대역)만 쓴다.
	 * IPv6 가입자는 보통 /64 대역을 통째로 받아서, 주소 전체로 세면 기기 한 대가 주소를 바꿔 가며 한도를 끝없이 새로 받는다.
	 */
	static String ipKeyPart(String clientIp) {
		if (clientIp == null || clientIp.isBlank()) {
			return "unknown";
		}
		// 콜론이 있으면 IPv6 표기다. 이름이 아니라 주소 표기만 해석하므로 DNS를 조회하지 않는다.
		if (clientIp.indexOf(':') >= 0) {
			try {
				InetAddress address = InetAddress.getByName(clientIp);
				if (address instanceof Inet6Address) {
					String prefix = HexFormat.of().formatHex(address.getAddress(), 0, 8);
					return prefix.substring(0, 4) + ":" + prefix.substring(4, 8) + ":" + prefix.substring(8, 12) + ":"
							+ prefix.substring(12, 16) + "::/64";
				}
				// IPv4를 IPv6로 감싼 표기(::ffff:192.0.2.1)는 IPv4로 읽힌다.
				return address.getHostAddress();
			}
			catch (UnknownHostException ex) {
				// 주소로 읽을 수 없는 값이다. 그대로 세되 키에 들어갈 수 없는 문자가 섞이지 않게 16진수로 바꾼다.
				return "invalid-" + HexFormat.of().formatHex(clientIp.getBytes(StandardCharsets.UTF_8));
			}
		}
		return clientIp;
	}

	// 비밀키 없는 해시는 Redis 내용이 새면 흔한 값을 대입해서 원문을 찾을 수 있다. 비밀키를 섞어 그것을 막는다.
	// Mac은 여러 스레드가 같이 쓸 수 없어 매번 새로 만든다.
	private String hmac(String value) {
		try {
			Mac mac = Mac.getInstance(HMAC_ALGORITHM);
			mac.init(this.hmacKey);
			return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)), 0, 16);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("HMAC을 계산할 수 없다", ex);
		}
	}

	/** 어느 한도에 걸렸는지 */
	enum Scope {

		/** 이 IP에서 이 아이디로 너무 많이 틀렸다. 같은 IP의 다른 아이디는 로그인할 수 있다. */
		LOGIN_ID_AND_IP,

		/** 이 IP에서 아이디와 무관하게 너무 많이 틀렸다. 이 IP에서는 누구도 새로 로그인할 수 없다. */
		IP

	}

	/**
	 * @param retryAfter 다시 시도할 수 있을 때까지 남은 시간
	 */
	record Blocked(Scope scope, Duration retryAfter) {
	}

}
