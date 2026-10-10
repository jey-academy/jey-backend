package com.jey.core.auth;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.jey.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 Redis에서 확인한다. 한도는 테스트에서 직접 만든 limiter로 정하고, 테스트끼리 섞이지 않게 IP와 아이디를 매번 새로 만든다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class LoginAttemptLimiterTest {

	private static final Duration WINDOW = Duration.ofMinutes(15);

	private static final AtomicInteger IP_SEQUENCE = new AtomicInteger();

	@Autowired
	StringRedisTemplate redis;

	private LoginAttemptLimiter limiter;

	private String ip;

	private String loginId;

	@BeforeEach
	void setUp() {
		limiter = new LoginAttemptLimiter(redis, new LoginAttemptProperties(WINDOW, 5, 8));
		ip = newIp();
		loginId = "desk-" + UUID.randomUUID().toString().substring(0, 8);
	}

	@Test
	void 한도까지는_허용하고_다음_시도부터_막는다() {
		for (int i = 0; i < 5; i++) {
			assertThat(limiter.countAttempt(loginId, ip)).isEmpty();
		}

		Optional<Duration> retryAfter = limiter.countAttempt(loginId, ip);

		assertThat(retryAfter).isPresent();
		assertThat(retryAfter.get()).isPositive().isLessThanOrEqualTo(WINDOW);
	}

	@Test
	void 아이디가_달라도_같은_IP의_시도가_한도를_넘으면_막는다() {
		for (int i = 0; i < 8; i++) {
			assertThat(limiter.countAttempt("other-" + i, ip)).isEmpty();
		}

		assertThat(limiter.countAttempt(loginId, ip)).isPresent();
	}

	// 누군가 밖에서 일부러 틀려도 학원 PC에서 쓰는 계정은 잠기지 않아야 한다.
	@Test
	void 다른_IP의_실패는_이_IP의_같은_아이디를_막지_않는다() {
		String attackerIp = newIp();
		for (int i = 0; i < 7; i++) {
			limiter.countAttempt(loginId, attackerIp);
		}
		assertThat(limiter.countAttempt(loginId, attackerIp)).isPresent();

		assertThat(limiter.countAttempt(loginId, ip)).isEmpty();
	}

	@Test
	void 성공하면_아이디와_IP_횟수를_지우고_IP_횟수를_되돌린다() {
		for (int i = 0; i < 4; i++) {
			limiter.countAttempt(loginId, ip);
		}
		limiter.countAttempt(loginId, ip);
		limiter.loginSucceeded(loginId, ip);

		// 아이디 기준은 처음부터 다시 센다. 성공한 시도는 IP 기준에서도 빠진다(실패 4회만 남는다).
		for (int i = 0; i < 4; i++) {
			assertThat(limiter.countAttempt(loginId, ip)).isEmpty();
		}
		assertThat(redis.opsForValue().get(ipKey(ip))).isEqualTo("8");
	}

	// 만료 시간이 없는 키가 생기면 그 아이디나 IP는 영원히 잠긴다.
	@Test
	void 키에는_만료_시간이_있다() {
		limiter.countAttempt(loginId, ip);
		limiter.countAttempt(loginId, ip);

		Set<String> keys = keysFor(ip);
		assertThat(keys).hasSize(2);
		keys.forEach(key -> assertThat(redis.getExpire(key, TimeUnit.SECONDS))
				.isBetween(1L, WINDOW.toSeconds()));
	}

	// 없는 키를 감소시키면 만료 시간 없는 -1 키가 생긴다.
	@Test
	void 성공_처리가_없는_키를_만들지_않는다() {
		limiter.loginSucceeded(loginId, ip);

		assertThat(keysFor(ip)).isEmpty();
	}

	// 막힌 뒤에 계속 시도해도 풀리는 시각이 뒤로 밀리지 않는다.
	@Test
	void 막힌_뒤의_시도가_만료_시간을_늘리지_않는다() {
		limiter = new LoginAttemptLimiter(redis, new LoginAttemptProperties(WINDOW, 1, 8));
		limiter.countAttempt(loginId, ip);
		redis.expire(idKey(ip), Duration.ofSeconds(30));

		assertThat(limiter.countAttempt(loginId, ip).orElseThrow()).isLessThanOrEqualTo(Duration.ofSeconds(30));
		assertThat(redis.getExpire(idKey(ip), TimeUnit.SECONDS)).isLessThanOrEqualTo(30L);
	}

	@Test
	void 대소문자와_공백이_달라도_같은_아이디로_센다() {
		limiter = new LoginAttemptLimiter(redis, new LoginAttemptProperties(WINDOW, 2, 8));
		limiter.countAttempt(loginId, ip);
		limiter.countAttempt("  " + loginId.toUpperCase() + " ", ip);

		assertThat(limiter.countAttempt(loginId, ip)).isPresent();
	}

	// 아이디 칸에 비밀번호를 잘못 넣는 일이 흔하다. 그 값이 Redis에 그대로 남으면 안 된다.
	@Test
	void Redis_키에_아이디_원문이_없다() {
		limiter.countAttempt(loginId, ip);
		limiter.countAttempt(null, ip);
		limiter.countAttempt("아이디 칸에 넣은 비밀번호!", ip);

		assertThat(keysFor(ip)).isNotEmpty()
				.allSatisfy(key -> assertThat(key).doesNotContain(loginId).doesNotContain("비밀번호")
						.matches("[a-z0-9:.\\-]+"));
	}

	// 비밀번호 해시 계산은 느리다. "확인 후 증가"로 만들면 동시에 보낸 요청이 모두 확인을 통과한다.
	@Test
	void 동시에_보내도_한도만큼만_허용된다() throws Exception {
		int requests = 50;
		ExecutorService executor = Executors.newFixedThreadPool(requests);
		CountDownLatch ready = new CountDownLatch(requests);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(requests);
		AtomicInteger allowed = new AtomicInteger();
		AtomicInteger failed = new AtomicInteger();
		try {
			for (int i = 0; i < requests; i++) {
				executor.submit(() -> {
					ready.countDown();
					try {
						start.await();
						if (limiter.countAttempt(loginId, ip).isEmpty()) {
							allowed.incrementAndGet();
						}
					}
					catch (Exception ex) {
						failed.incrementAndGet();
					}
					finally {
						done.countDown();
					}
				});
			}
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
		}
		finally {
			executor.shutdownNow();
		}

		assertThat(failed).hasValue(0);
		assertThat(allowed).hasValue(5);
	}

	private Set<String> keysFor(String ip) {
		return redis.keys("jey:auth:login-attempts:*" + ip);
	}

	private String idKey(String ip) {
		return keysFor(ip).stream().filter(key -> key.contains(":id:")).findFirst().orElseThrow();
	}

	private static String ipKey(String ip) {
		return "jey:auth:login-attempts:ip:" + ip;
	}

	// 문서용으로 예약된 대역(198.51.100.0/24)에서 테스트마다 다른 주소를 쓴다.
	private static String newIp() {
		int n = IP_SEQUENCE.incrementAndGet();
		return "198.51." + (100 + n / 250) + "." + (n % 250 + 1);
	}

}
