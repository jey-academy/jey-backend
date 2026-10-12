package com.jey.core.web;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.jey.TestcontainersConfiguration;
import com.jey.core.shared.Actor;
import com.jey.core.web.IdempotencyStore.Claim;
import com.jey.core.web.IdempotencyStore.StoredResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 실제 Redis에서 확인한다. 테스트끼리 섞이지 않게 계정과 키를 매번 새로 만든다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class IdempotencyStoreTest {

	private static final AtomicLong ACCOUNT_SEQUENCE = new AtomicLong(
			ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L));

	private static final String FINGERPRINT = "a".repeat(64);

	private static final StoredResponse CREATED = new StoredResponse(201, "application/json",
			"{\"id\":7,\"name\":\"김철수\"}".getBytes(StandardCharsets.UTF_8));

	@Autowired
	IdempotencyStore store;

	@Autowired
	StringRedisTemplate redis;

	private Actor.User account;

	private String key;

	@BeforeEach
	void setUp() {
		account = new Actor.User(ACCOUNT_SEQUENCE.incrementAndGet());
		key = UUID.randomUUID().toString();
	}

	@Test
	void 처음_온_키는_선점하고_처리_중인_키는_다시_선점하지_못한다() {
		assertThat(store.claim(account, key, FINGERPRINT)).isInstanceOf(Claim.Acquired.class);

		assertThat(store.claim(account, key, FINGERPRINT)).isInstanceOf(Claim.InProgress.class);
		assertThat(store.claim(account, key, FINGERPRINT)).isInstanceOf(Claim.InProgress.class);
	}

	// 같은 키로 다른 일을 시키는 것은 화면의 잘못이다. 앞의 응답을 돌려주면 하지 않은 일을 했다고 답하게 된다.
	@Test
	void 같은_키에_내용이_다르면_다른_요청으로_본다() {
		Claim.Acquired acquired = acquire(key);
		String other = "b".repeat(64);

		assertThat(store.claim(account, key, other)).isInstanceOf(Claim.Mismatch.class);

		store.complete(account, key, acquired, CREATED);
		assertThat(store.claim(account, key, other)).isInstanceOf(Claim.Mismatch.class);
	}

	@Test
	void 끝난_키는_보관한_응답을_그대로_돌려준다() {
		store.complete(account, key, acquire(key), CREATED);

		Claim again = store.claim(account, key, FINGERPRINT);

		assertThat(again).isInstanceOfSatisfying(Claim.Completed.class, completed -> {
			assertThat(completed.response().status()).isEqualTo(201);
			assertThat(completed.response().contentType()).isEqualTo("application/json");
			// 글자가 아니라 바이트가 같아야 한다. 한글이 깨지거나 공백이 달라지면 처음의 응답이 아니다.
			assertThat(completed.response().body()).isEqualTo(CREATED.body());
		});
		assertThat(store.claim(account, key, FINGERPRINT)).isInstanceOf(Claim.Completed.class);
	}

	// 본문이 없는 응답(204)과 타입이 없는 응답도 보관한다.
	@Test
	void 본문과_타입이_없는_응답도_보관한다() {
		store.complete(account, key, acquire(key), new StoredResponse(204, null, new byte[0]));

		assertThat(store.claim(account, key, FINGERPRINT)).isInstanceOfSatisfying(Claim.Completed.class,
				completed -> {
					assertThat(completed.response().status()).isEqualTo(204);
					assertThat(completed.response().contentType()).isNull();
					assertThat(completed.response().body()).isEmpty();
				});
	}

	@Test
	void 끝난_키는_보관_기간_동안_남는다() {
		store.complete(account, key, acquire(key), CREATED);

		assertThat(redis.getExpire(IdempotencyStore.redisKey(account, key), TimeUnit.HOURS)).isBetween(23L, 24L);
	}

	// 처리하던 서버가 죽으면 "처리 중"만 남는다. 영영 남으면 그 키로는 다시 시도할 수 없다.
	@Test
	void 처리_중_표시는_임대_시간_동안만_남는다() {
		acquire(key);

		assertThat(redis.getExpire(IdempotencyStore.redisKey(account, key), TimeUnit.SECONDS)).isBetween(100L, 120L);
	}

	// 서버 오류로 끝난 요청은 다시 시도할 수 있어야 한다.
	@Test
	void 선점을_풀면_같은_키로_다시_선점할_수_있다() {
		Claim.Acquired first = acquire(key);

		store.release(account, key, first);

		assertThat(store.claim(account, key, FINGERPRINT)).isInstanceOf(Claim.Acquired.class);
	}

	@Test
	void 임대가_끝나면_같은_키로_다시_선점할_수_있다() throws InterruptedException {
		acquire(key);

		expire(key);

		assertThat(store.claim(account, key, FINGERPRINT)).isInstanceOf(Claim.Acquired.class);
	}

	// 임대가 끝난 뒤 같은 키가 다시 선점됐다. 늦게 끝난 앞의 요청이 그 선점을 덮거나 지우면 안 된다.
	@Test
	void 임대가_끝난_뒤_늦게_끝낸_쪽은_새_선점을_건드리지_못한다() throws InterruptedException {
		Claim.Acquired slow = acquire(key);
		expire(key);
		Claim.Acquired next = acquire(key);

		// 넣지 못했다고 알려 준다. 부른 쪽이 로그를 남긴다.
		assertThat(store.complete(account, key, slow, new StoredResponse(500, null, new byte[0]))).isFalse();
		assertThat(store.release(account, key, slow)).isFalse();

		assertThat(store.claim(account, key, FINGERPRINT)).isInstanceOf(Claim.InProgress.class);
		assertThat(store.complete(account, key, next, CREATED)).isTrue();
		assertThat(store.claim(account, key, FINGERPRINT)).isInstanceOf(Claim.Completed.class);
	}

	// 임대가 끝나 키가 사라졌거나 저장소가 비워진 경우다. 없는 선점을 끝내면서 응답만 있는 기록을 만들지 않는다.
	@Test
	void 선점이_사라진_뒤에는_끝내거나_풀_수_없다() throws InterruptedException {
		Claim.Acquired gone = acquire(key);
		expire(key);

		assertThat(store.complete(account, key, gone, CREATED)).isFalse();
		assertThat(store.release(account, key, gone)).isFalse();

		assertThat(redis.hasKey(IdempotencyStore.redisKey(account, key))).isFalse();
	}

	// 끝낸 뒤에 같은 선점으로 푸는 코드가 생기면(예: finally에서 풀기) 보관한 응답이 지워지고 같은 키가 다시 실행된다.
	@Test
	void 끝낸_선점은_풀거나_다시_끝낼_수_없다() {
		Claim.Acquired claim = acquire(key);
		assertThat(store.complete(account, key, claim, CREATED)).isTrue();

		assertThat(store.release(account, key, claim)).isFalse();
		assertThat(store.complete(account, key, claim, new StoredResponse(409, "application/problem+json",
				"{\"code\":\"X\"}".getBytes(StandardCharsets.UTF_8)))).isFalse();

		assertThat(store.claim(account, key, FINGERPRINT)).isInstanceOfSatisfying(Claim.Completed.class,
				completed -> {
					assertThat(completed.response().status()).isEqualTo(201);
					assertThat(completed.response().body()).isEqualTo(CREATED.body());
				});
	}

	// 깨진 기록은 보관 기간 내내 같은 오류를 낸다. 어느 키인지 알아야 지울 수 있다.
	@Test
	void 보관한_응답을_읽지_못하면_어느_키인지_알려_준다() {
		String redisKey = IdempotencyStore.redisKey(account, key);
		store.complete(account, key, acquire(key), CREATED);
		redis.opsForHash().put(redisKey, "status", "이백일");

		assertThatThrownBy(() -> store.claim(account, key, FINGERPRINT)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining(redisKey);

		redis.opsForHash().put(redisKey, "status", "201");
		redis.opsForHash().put(redisKey, "body", "base64가 아니다!");
		assertThatThrownBy(() -> store.claim(account, key, FINGERPRINT)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining(redisKey);
	}

	@Test
	void 로그에_남기는_키의_식별자는_저장소_키에_든_해시의_앞부분이다() {
		String id = IdempotencyStore.keyId(key);

		assertThat(id).hasSize(12).doesNotContain(key.substring(0, 8));
		assertThat(IdempotencyStore.redisKey(account, key)).contains(":" + id);
	}

	@Test
	void 보관하는_응답의_상태는_HTTP_상태_코드여야_한다() {
		assertThatThrownBy(() -> new StoredResponse(0, null, new byte[0])).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new StoredResponse(99, null, new byte[0]))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new StoredResponse(600, null, new byte[0]))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new StoredResponse(200, null, null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new Claim.Acquired(null)).isInstanceOf(NullPointerException.class);
		assertThat(new StoredResponse(100, null, new byte[0]).status()).isEqualTo(100);
		assertThat(new StoredResponse(599, null, new byte[0]).status()).isEqualTo(599);
	}

	// 키는 화면이 만든다. 다른 계정이 같은 키를 쓰면 남의 응답을 받게 된다.
	@Test
	void 계정이_다르면_같은_키도_따로_본다() {
		store.complete(account, key, acquire(key), CREATED);

		Actor.User other = new Actor.User(ACCOUNT_SEQUENCE.incrementAndGet());

		assertThat(store.claim(other, key, FINGERPRINT)).isInstanceOf(Claim.Acquired.class);
	}

	@Test
	void 동시에_선점해도_하나만_선점한다() throws Exception {
		int threads = 24;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<Claim>> results = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				results.add(pool.submit(() -> {
					ready.countDown();
					start.await();
					return store.claim(account, key, FINGERPRINT);
				}));
			}
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();

			List<Claim> claims = new ArrayList<>();
			for (Future<Claim> result : results) {
				claims.add(result.get(10, TimeUnit.SECONDS));
			}

			assertThat(claims).filteredOn(Claim.Acquired.class::isInstance).hasSize(1);
			assertThat(claims).filteredOn(Claim.InProgress.class::isInstance).hasSize(threads - 1);
		}
		finally {
			pool.shutdownNow();
		}
	}

	// 키에 무엇을 넣어 보낼지 모른다(주문번호, 이름). Redis에 그대로 남기지 않는다.
	@Test
	void 키의_원문은_저장소의_키에_들어가지_않는다() {
		String revealing = "hong-gildong-20261012";

		acquire(revealing);

		assertThat(redis.keys("jey:web:idempotency:" + account.accountId() + ":*")).hasSize(1)
				.allSatisfy(stored -> assertThat(stored).doesNotContain("hong").doesNotContain("20261012"));
	}

	// 만료 시간이 없는 키는 영원히 "처리 중"이다. 이 장치는 그런 키를 만들지 않지만 밖에서 생겼다면 고친다.
	@Test
	void 만료_시간이_없는_키는_임대_시간을_붙인다() {
		redis.opsForHash().put(IdempotencyStore.redisKey(account, key), "state", "processing");
		redis.opsForHash().put(IdempotencyStore.redisKey(account, key), "fp", FINGERPRINT);

		assertThat(store.claim(account, key, FINGERPRINT)).isInstanceOf(Claim.InProgress.class);

		assertThat(redis.getExpire(IdempotencyStore.redisKey(account, key), TimeUnit.SECONDS)).isBetween(1L, 120L);
	}

	@Test
	void 보관_기간과_임대_시간은_너무_짧으면_안_된다() {
		// 단위를 빼먹은 "24"는 24밀리초로 읽힌다.
		assertThatThrownBy(() -> new IdempotencyProperties(Duration.ofMillis(24), Duration.ofMinutes(2)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("retention");
		assertThatThrownBy(() -> new IdempotencyProperties(Duration.ofHours(24), Duration.ofMillis(2)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("processing-lease");
		// 임대가 보관 기간보다 길면 끝난 응답이 처리 중 표시보다 먼저 사라진다. 둘을 바꿔 적은 실수다.
		assertThatThrownBy(() -> new IdempotencyProperties(Duration.ofMinutes(1), Duration.ofMinutes(2)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("processing-lease");
		assertThatThrownBy(() -> new IdempotencyProperties(null, Duration.ofMinutes(2)))
				.isInstanceOf(IllegalArgumentException.class);
		// "2m"을 "2h"로 잘못 적으면 서버가 죽은 요청의 키가 두 시간 동안 거부된다.
		assertThatThrownBy(() -> new IdempotencyProperties(Duration.ofHours(24), Duration.ofMinutes(10).plusSeconds(1)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("processing-lease");
		assertThat(new IdempotencyProperties(Duration.ofHours(24), Duration.ofMinutes(10)).processingLease())
				.isEqualTo(Duration.ofMinutes(10));
		assertThat(new IdempotencyProperties(Duration.ofHours(24), Duration.ofMinutes(2)).retention())
				.isEqualTo(Duration.ofHours(24));
	}

	private Claim.Acquired acquire(String idempotencyKey) {
		Claim claim = store.claim(account, idempotencyKey, FINGERPRINT);
		assertThat(claim).isInstanceOf(Claim.Acquired.class);
		return (Claim.Acquired) claim;
	}

	// 임대가 끝난 것처럼 만든다. 짧은 임대를 두고 기다리면 느린 실행에서 테스트가 흔들린다.
	private void expire(String idempotencyKey) throws InterruptedException {
		String redisKey = IdempotencyStore.redisKey(account, idempotencyKey);
		redis.expire(redisKey, Duration.ofMillis(1));
		for (int i = 0; i < 500 && Boolean.TRUE.equals(redis.hasKey(redisKey)); i++) {
			Thread.sleep(10);
		}
		assertThat(redis.hasKey(redisKey)).isFalse();
	}

}
