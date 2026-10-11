package com.jey.core.auth;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.jey.TestcontainersConfiguration;
import com.jey.core.auth.api.AccountRateLimiter;
import com.jey.core.auth.api.AccountRateLimiter.Blocked;
import com.jey.core.auth.api.RateLimit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 실제 Redis에서 확인한다. 테스트끼리 섞이지 않게 계정 ID를 매번 새로 만든다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class RedisAccountRateLimiterTest {

	private static final Duration WINDOW = Duration.ofMinutes(15);

	private static final RateLimit LIMIT = new RateLimit("test.limited-thing", 5, WINDOW);

	private static final AtomicLong ACCOUNT_SEQUENCE = new AtomicLong(
			ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L));

	@Autowired
	AccountRateLimiter limiter;

	@Autowired
	StringRedisTemplate redis;

	private long accountId;

	@BeforeEach
	void setUp() {
		accountId = ACCOUNT_SEQUENCE.incrementAndGet();
	}

	@Test
	void 한도까지는_통과하고_그다음부터_막는다() {
		for (int i = 0; i < 5; i++) {
			assertThat(limiter.tryAcquire(LIMIT, accountId)).isEmpty();
		}

		Optional<Blocked> blocked = limiter.tryAcquire(LIMIT, accountId);

		assertThat(blocked).isPresent();
		assertThat(blocked.get().retryAfter()).isPositive().isLessThanOrEqualTo(WINDOW);
	}

	// 막힌 요청까지 세면 계속 누르는 동안 횟수가 끝없이 는다.
	@Test
	void 막힌_요청은_세지_않는다() {
		acquire(LIMIT, accountId, 5);

		for (int i = 0; i < 20; i++) {
			assertThat(limiter.tryAcquire(LIMIT, accountId)).isPresent();
		}

		assertThat(redis.opsForValue().get(countKey(LIMIT, accountId))).isEqualTo("5");
	}

	// 고정 구간이다. 처음 센 때부터 window가 지나면 풀린다. 그 뒤에 센 것이 만료를 미루지 않는다.
	@Test
	void 만료_시간은_처음_셀_때만_정한다() {
		limiter.tryAcquire(LIMIT, accountId);
		redis.expire(countKey(LIMIT, accountId), Duration.ofMinutes(3));

		limiter.tryAcquire(LIMIT, accountId);

		assertThat(redis.getExpire(countKey(LIMIT, accountId), TimeUnit.SECONDS)).isLessThanOrEqualTo(180);
	}

	@Test
	void 구간이_지나면_풀린다() throws InterruptedException {
		RateLimit brief = new RateLimit("test.brief-thing", 2, Duration.ofMillis(300));
		acquire(brief, accountId, 2);
		assertThat(limiter.tryAcquire(brief, accountId)).isPresent();

		Thread.sleep(500);

		assertThat(limiter.tryAcquire(brief, accountId)).isEmpty();
	}

	@Test
	void 되돌리면_한_번을_다시_쓸_수_있다() {
		acquire(LIMIT, accountId, 5);

		limiter.refund(LIMIT, accountId);

		assertThat(limiter.tryAcquire(LIMIT, accountId)).isEmpty();
		assertThat(limiter.tryAcquire(LIMIT, accountId)).isPresent();
	}

	// 없는 키를 줄이면 만료 시간 없는 -1 키가 생기고, 그 키는 영영 남는다.
	@Test
	void 센_적이_없으면_되돌려도_키가_생기지_않는다() {
		limiter.refund(LIMIT, accountId);

		assertThat(redis.hasKey(countKey(LIMIT, accountId))).isFalse();
	}

	@Test
	void 되돌려도_0보다_작아지지_않고_만료_시간은_그대로다() {
		limiter.tryAcquire(LIMIT, accountId);

		limiter.refund(LIMIT, accountId);
		limiter.refund(LIMIT, accountId);

		assertThat(redis.opsForValue().get(countKey(LIMIT, accountId))).isEqualTo("0");
		assertThat(redis.getExpire(countKey(LIMIT, accountId), TimeUnit.SECONDS)).isPositive();
	}

	@Test
	void 계정이_다르면_따로_센다() {
		acquire(LIMIT, accountId, 5);

		assertThat(limiter.tryAcquire(LIMIT, ACCOUNT_SEQUENCE.incrementAndGet())).isEmpty();
	}

	@Test
	void 이름이_다르면_따로_센다() {
		acquire(LIMIT, accountId, 5);

		assertThat(limiter.tryAcquire(new RateLimit("test.other-thing", 5, WINDOW), accountId)).isEmpty();
	}

	// 확인과 증가가 한 번에 되지 않으면 동시에 온 요청이 모두 확인을 통과한다.
	@Test
	void 동시에_요청해도_한도만큼만_통과한다() throws Exception {
		int threads = 24;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<Optional<Blocked>>> results = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				results.add(pool.submit(() -> {
					ready.countDown();
					start.await();
					return limiter.tryAcquire(LIMIT, accountId);
				}));
			}
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();

			int passed = 0;
			int firstBlocks = 0;
			for (Future<Optional<Blocked>> result : results) {
				Optional<Blocked> blocked = result.get(10, TimeUnit.SECONDS);
				if (blocked.isEmpty()) {
					passed++;
				}
				else if (blocked.get().firstInWindow()) {
					firstBlocks++;
				}
			}

			assertThat(passed).isEqualTo(5);
			// 막힌 사실은 한 번만 기록해야 한다. 동시에 막힌 요청 중에서도 하나만 "처음"이다.
			assertThat(firstBlocks).isEqualTo(1);
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void 한_구간에서_처음_막힌_것은_한_번뿐이다() {
		acquire(LIMIT, accountId, 5);

		assertThat(limiter.tryAcquire(LIMIT, accountId).orElseThrow().firstInWindow()).isTrue();
		assertThat(limiter.tryAcquire(LIMIT, accountId).orElseThrow().firstInWindow()).isFalse();

		// 되돌려서 풀렸다가 다시 막혀도 같은 구간이면 처음이 아니다.
		limiter.refund(LIMIT, accountId);
		limiter.tryAcquire(LIMIT, accountId);
		assertThat(limiter.tryAcquire(LIMIT, accountId).orElseThrow().firstInWindow()).isFalse();
	}

	@Test
	void 구간이_바뀌면_다시_처음_막힌_것으로_알린다() throws InterruptedException {
		RateLimit brief = new RateLimit("test.brief-noted", 1, Duration.ofMillis(300));
		acquire(brief, accountId, 1);
		assertThat(limiter.tryAcquire(brief, accountId).orElseThrow().firstInWindow()).isTrue();

		Thread.sleep(500);
		acquire(brief, accountId, 1);

		assertThat(limiter.tryAcquire(brief, accountId).orElseThrow().firstInWindow()).isTrue();
	}

	// 만료 시간이 없는 키는 영원히 잠근다. 이 장치는 그런 키를 만들지 않지만 밖에서 생겼다면 고친다.
	@Test
	void 만료_시간이_없는_키는_고치고_알린다(CapturedOutput output) {
		redis.opsForValue().set(countKey(LIMIT, accountId), "5");

		Optional<Blocked> blocked = limiter.tryAcquire(LIMIT, accountId);

		assertThat(blocked).isPresent();
		assertThat(blocked.get().retryAfter()).isPositive().isLessThanOrEqualTo(WINDOW);
		assertThat(redis.getExpire(countKey(LIMIT, accountId), TimeUnit.SECONDS)).isPositive();
		assertThat(output.getOut()).contains("만료 시간이 없는 횟수 제한 키를 고쳤다");
	}

	@Test
	void 한도의_이름은_모듈과_대상을_점으로_이은_소문자다() {
		assertThatThrownBy(() -> new RateLimit("PhoneReveal", 5, WINDOW)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RateLimit("member", 5, WINDOW)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RateLimit("member.phone:reveal", 5, WINDOW))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RateLimit(null, 5, WINDOW)).isInstanceOf(NullPointerException.class);
	}

	@Test
	void 한도는_1_이상이고_구간은_0보다_길다() {
		assertThatThrownBy(() -> new RateLimit("member.phone-reveal", 0, WINDOW))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RateLimit("member.phone-reveal", 5, Duration.ZERO))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RateLimit("member.phone-reveal", 5, Duration.ofMinutes(-1)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RateLimit("member.phone-reveal", 5, null))
				.isInstanceOf(NullPointerException.class);
	}

	private void acquire(RateLimit limit, long account, int times) {
		for (int i = 0; i < times; i++) {
			assertThat(limiter.tryAcquire(limit, account)).isEmpty();
		}
	}

	private static String countKey(RateLimit limit, long account) {
		return "jey:auth:account-limit:" + limit.name() + ":" + account;
	}

}
