package com.jey.core.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.jey.core.shared.Actor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 멱등 키마다 "처리 중"과 끝난 요청의 응답을 Redis에 둔다(ADR-0027).
 *
 * <p>계정 하나와 키 하나에 해시 키 하나를 쓴다. 처음 온 키는 "처리 중"으로 선점하고, 끝나면 응답을 넣어 보관 기간 동안 둔다.
 * 선점한 쪽만 끝내거나 풀 수 있다. 임대가 끝나 다른 요청이 다시 선점한 뒤에 늦게 끝난 쪽이 그것을 덮지 못하게 선점마다 표식을 준다.
 */
@Component
@EnableConfigurationProperties(IdempotencyProperties.class)
class IdempotencyStore {

	private static final Logger log = LoggerFactory.getLogger(IdempotencyStore.class);

	private static final String KEY_PREFIX = "jey:web:idempotency:";

	// 확인과 선점을 한 번에 한다. 따로 하면 동시에 온 같은 키가 둘 다 선점한다.
	// 만료 시간이 없는 키는 영원히 "처리 중"이므로, 밖에서 생겼다면(수동 조작, 복원) 임대 시간을 붙인다.
	// KEYS: 키 / ARGV: 요청의 지문, 선점의 표식, 임대(밀리초)
	// 반환: 결과의 이름, (끝난 경우) 상태, 타입, 본문
	private static final RedisScript<List> CLAIM = new DefaultRedisScript<>("""
			if redis.call('EXISTS', KEYS[1]) == 0 then
				redis.call('HSET', KEYS[1], 'state', 'processing', 'fp', ARGV[1], 'token', ARGV[2])
				redis.call('PEXPIRE', KEYS[1], ARGV[3])
				return {'acquired'}
			end
			if redis.call('PTTL', KEYS[1]) == -1 then
				redis.call('PEXPIRE', KEYS[1], ARGV[3])
			end
			local stored = redis.call('HMGET', KEYS[1], 'state', 'fp', 'status', 'type', 'body')
			if stored[2] ~= ARGV[1] then
				return {'mismatch'}
			end
			if stored[1] == 'done' then
				return {'done', stored[3], stored[4] or '', stored[5] or ''}
			end
			return {'processing'}
			""", List.class);

	// 내 선점일 때만 응답을 넣는다. 임대가 끝나 키가 사라졌거나 다른 요청이 다시 선점했으면 넣지 않는다.
	// KEYS: 키 / ARGV: 선점의 표식, 상태, 타입, 본문, 보관 기간(밀리초)
	private static final RedisScript<Long> COMPLETE = new DefaultRedisScript<>("""
			if redis.call('HGET', KEYS[1], 'token') == ARGV[1] then
				redis.call('HSET', KEYS[1], 'state', 'done', 'status', ARGV[2], 'type', ARGV[3], 'body', ARGV[4])
				redis.call('PEXPIRE', KEYS[1], ARGV[5])
				return 1
			end
			return 0
			""", Long.class);

	// 내 선점일 때만 지운다.
	private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>("""
			if redis.call('HGET', KEYS[1], 'token') == ARGV[1] then
				redis.call('DEL', KEYS[1])
				return 1
			end
			return 0
			""", Long.class);

	private final StringRedisTemplate redis;

	private final IdempotencyProperties properties;

	IdempotencyStore(StringRedisTemplate redis, IdempotencyProperties properties) {
		this.redis = redis;
		this.properties = properties;
	}

	/**
	 * 이 키로 일을 해도 되는지 본다. 처음 온 키면 "처리 중"으로 선점한다.
	 *
	 * @param fingerprint 요청의 내용을 줄인 값. 같은 키에 다른 내용이 왔는지 가리는 데 쓴다
	 */
	Claim claim(Actor.User account, String idempotencyKey, String fingerprint) {
		Objects.requireNonNull(fingerprint, "fingerprint");
		String token = UUID.randomUUID().toString();
		List<?> result = redis.execute(CLAIM, List.of(redisKey(account, idempotencyKey)), fingerprint, token,
				String.valueOf(properties.processingLease().toMillis()));
		if (result == null || result.isEmpty() || !(result.getFirst() instanceof String outcome)) {
			throw new IllegalStateException("멱등 키 스크립트의 결과가 예상과 다르다: " + result);
		}
		return switch (outcome) {
			case "acquired" -> new Claim.Acquired(token);
			case "processing" -> new Claim.InProgress();
			case "mismatch" -> new Claim.Mismatch();
			case "done" -> new Claim.Completed(storedResponse(result));
			default -> throw new IllegalStateException("멱등 키 스크립트의 결과가 예상과 다르다: " + result);
		};
	}

	/**
	 * 일을 끝냈다. 응답을 보관 기간 동안 둔다. 그사이 임대가 끝나 내 선점이 아니게 됐으면 넣지 않고 로그만 남긴다.
	 */
	void complete(Actor.User account, String idempotencyKey, Claim.Acquired claim, StoredResponse response) {
		Long stored = redis.execute(COMPLETE, List.of(redisKey(account, idempotencyKey)), claim.token(),
				String.valueOf(response.status()), (response.contentType() != null) ? response.contentType() : "",
				Base64.getEncoder().encodeToString(response.body()),
				String.valueOf(properties.retention().toMillis()));
		if (stored == null || stored == 0) {
			// 요청이 임대보다 오래 걸렸다. 그사이 같은 키의 요청이 다시 실행됐을 수 있다.
			log.error("멱등 키의 응답을 보관하지 못했다. 처리가 임대 시간({})보다 오래 걸렸다: accountId={}",
					properties.processingLease(), account.accountId());
		}
	}

	/**
	 * 일을 끝내지 못했다. 같은 키로 다시 시도할 수 있게 선점을 푼다.
	 */
	void release(Actor.User account, String idempotencyKey, Claim.Acquired claim) {
		redis.execute(RELEASE, List.of(redisKey(account, idempotencyKey)), claim.token());
	}

	// 키의 원문은 넣지 않는다. 화면이 키에 무엇을 넣어 보낼지 모르고, 길이도 제각각이다.
	static String redisKey(Actor.User account, String idempotencyKey) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(idempotencyKey.getBytes(StandardCharsets.UTF_8));
			return KEY_PREFIX + account.accountId() + ":" + HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256을 쓸 수 없다", ex);
		}
	}

	private static StoredResponse storedResponse(List<?> result) {
		if (result.size() != 4 || !result.stream().allMatch(String.class::isInstance)) {
			throw new IllegalStateException("보관한 응답의 모양이 예상과 다르다");
		}
		String contentType = (String) result.get(2);
		return new StoredResponse(Integer.parseInt((String) result.get(1)), contentType.isEmpty() ? null : contentType,
				Base64.getDecoder().decode((String) result.get(3)));
	}

	/** 이 키로 일을 해도 되는지 본 결과. */
	sealed interface Claim {

		/**
		 * 선점했다. 일을 하고 {@link #complete}나 {@link #release}로 끝낸다.
		 *
		 * @param token 이 선점의 표식
		 */
		record Acquired(String token) implements Claim {
		}

		/** 같은 키의 같은 요청이 아직 처리 중이다. */
		record InProgress() implements Claim {
		}

		/** 같은 키로 다른 내용의 요청이 이미 왔다. */
		record Mismatch() implements Claim {
		}

		/** 이미 끝난 요청이다. 보관한 응답을 돌려준다. */
		record Completed(StoredResponse response) implements Claim {
		}

	}

	/**
	 * @param contentType 없으면 {@code null}
	 * @param body 본문이 없으면 빈 배열
	 */
	record StoredResponse(int status, String contentType, byte[] body) {

		StoredResponse {
			Objects.requireNonNull(body, "body");
		}

	}

}
