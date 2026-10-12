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
 * 끝낸 선점은 다시 끝내거나 풀 수 없다. 보관한 응답은 보관 기간이 끝나야 사라진다.
 */
@Component
@EnableConfigurationProperties(IdempotencyProperties.class)
class IdempotencyStore {

	private static final String KEY_PREFIX = "jey:web:idempotency:";

	private static final int KEY_ID_LENGTH = 12;

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

	// 처리 중인 내 선점일 때만 응답을 넣는다. 임대가 끝나 키가 사라졌거나 다른 요청이 다시 선점했으면 넣지 않는다.
	// 이미 끝낸 선점도 건드리지 않는다. 보관한 응답이 뒤에 온 것으로 바뀌면 다시 보낸 쪽이 처음과 다른 답을 받는다.
	// KEYS: 키 / ARGV: 선점의 표식, 상태, 타입, 본문, 보관 기간(밀리초)
	private static final RedisScript<Long> COMPLETE = new DefaultRedisScript<>("""
			local held = redis.call('HMGET', KEYS[1], 'token', 'state')
			if held[1] == ARGV[1] and held[2] == 'processing' then
				redis.call('HSET', KEYS[1], 'state', 'done', 'status', ARGV[2], 'type', ARGV[3], 'body', ARGV[4])
				redis.call('PEXPIRE', KEYS[1], ARGV[5])
				return 1
			end
			return 0
			""", Long.class);

	// 처리 중인 내 선점일 때만 지운다. 끝낸 선점을 지우면 보관한 응답이 사라져 같은 키가 다시 실행된다.
	private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>("""
			local held = redis.call('HMGET', KEYS[1], 'token', 'state')
			if held[1] == ARGV[1] and held[2] == 'processing' then
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
		String redisKey = redisKey(account, idempotencyKey);
		List<?> result = redis.execute(CLAIM, List.of(redisKey), fingerprint, token,
				String.valueOf(properties.processingLease().toMillis()));
		if (result == null || result.isEmpty() || !(result.getFirst() instanceof String outcome)) {
			throw new IllegalStateException("멱등 키 스크립트의 결과가 예상과 다르다: key=" + redisKey);
		}
		return switch (outcome) {
			case "acquired" -> new Claim.Acquired(token);
			case "processing" -> new Claim.InProgress();
			case "mismatch" -> new Claim.Mismatch();
			case "done" -> new Claim.Completed(storedResponse(result, redisKey));
			default -> throw new IllegalStateException(
					"멱등 키 스크립트의 결과가 예상과 다르다: key=" + redisKey + " outcome=" + outcome);
		};
	}

	/**
	 * 일을 끝냈다. 응답을 보관 기간 동안 둔다. 처리 중인 내 선점이 아니게 됐으면 넣지 않는다.
	 *
	 * @return 보관했으면 {@code true}. {@code false}면 선점이 사라졌거나(임대가 끝남, 저장소가 비워짐) 다른 요청이 다시 선점한 것이다.
	 * 어느 쪽인지는 가리지 못한다
	 */
	boolean complete(Actor.User account, String idempotencyKey, Claim.Acquired claim, StoredResponse response) {
		Long stored = redis.execute(COMPLETE, List.of(redisKey(account, idempotencyKey)), claim.token(),
				String.valueOf(response.status()), (response.contentType() != null) ? response.contentType() : "",
				Base64.getEncoder().encodeToString(response.body()),
				String.valueOf(properties.retention().toMillis()));
		return stored != null && stored == 1;
	}

	/**
	 * 일을 끝내지 못했다. 같은 키로 다시 시도할 수 있게 선점을 푼다. 처리 중인 내 선점이 아니게 됐으면 건드리지 않는다.
	 *
	 * @return 풀었으면 {@code true}
	 */
	boolean release(Actor.User account, String idempotencyKey, Claim.Acquired claim) {
		Long released = redis.execute(RELEASE, List.of(redisKey(account, idempotencyKey)), claim.token());
		return released != null && released == 1;
	}

	// 키의 원문은 넣지 않는다. 화면이 키에 무엇을 넣어 보낼지 모르고, 길이도 제각각이다.
	static String redisKey(Actor.User account, String idempotencyKey) {
		return KEY_PREFIX + account.accountId() + ":" + digest(idempotencyKey);
	}

	/** 로그에 남기는 키의 식별자. 저장소 키에 든 해시의 앞부분이라 로그와 저장소를 맞춰 볼 수 있다. 키의 원문은 로그에 남기지 않는다. */
	static String keyId(String idempotencyKey) {
		return digest(idempotencyKey).substring(0, KEY_ID_LENGTH);
	}

	private static String digest(String idempotencyKey) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(idempotencyKey.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256을 쓸 수 없다", ex);
		}
	}

	// 깨진 기록은 보관 기간 내내 같은 오류를 낸다. 어느 키인지 알아야 지울 수 있다.
	private static StoredResponse storedResponse(List<?> result, String redisKey) {
		if (result.size() != 4 || !result.stream().allMatch(String.class::isInstance)) {
			throw new IllegalStateException("보관한 응답의 모양이 예상과 다르다: key=" + redisKey);
		}
		String contentType = (String) result.get(2);
		try {
			return new StoredResponse(Integer.parseInt((String) result.get(1)),
					contentType.isEmpty() ? null : contentType, Base64.getDecoder().decode((String) result.get(3)));
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException("보관한 응답을 읽지 못했다: key=" + redisKey, ex);
		}
	}

	/** 이 키로 일을 해도 되는지 본 결과. */
	sealed interface Claim {

		/**
		 * 선점했다. 일을 하고 {@link #complete}나 {@link #release}로 끝낸다.
		 *
		 * @param token 이 선점의 표식
		 */
		record Acquired(String token) implements Claim {

			public Acquired {
				Objects.requireNonNull(token, "token");
			}

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
	 * 보관하는 응답. 상태와 타입과 본문만 둔다. 그 밖의 응답 헤더는 보관하지 않는다.
	 *
	 * <p>본문이 배열이라 {@code equals}는 같은 배열일 때만 참이다. 내용을 견주려면 본문을 따로 견준다.
	 *
	 * @param contentType 없으면 {@code null}
	 * @param body 본문이 없으면 빈 배열
	 */
	record StoredResponse(int status, String contentType, byte[] body) {

		StoredResponse {
			if (status < 100 || status > 599) {
				throw new IllegalArgumentException("HTTP 상태 코드가 아니다: " + status);
			}
			Objects.requireNonNull(body, "body");
		}

	}

}
