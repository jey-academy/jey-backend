package com.jey.core.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import com.jey.TestcontainersConfiguration;
import com.jey.core.auth.LoginAttemptLimiter.Blocked;
import com.jey.core.auth.LoginAttemptLimiter.Scope;
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

// 실제 Redis에서 확인한다. 한도는 테스트에서 직접 만든 limiter로 정하고, 테스트끼리 섞이지 않게 IP와 아이디를 매번 새로 만든다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class LoginAttemptLimiterTest {

	private static final Duration WINDOW = Duration.ofMinutes(15);

	private static final String KEY_PREFIX = "jey:auth:login-attempts:";

	private static final String HMAC_KEY = "test-only-login-attempt-hmac-key-0123456789";

	private static final AtomicInteger IP_SEQUENCE = new AtomicInteger();

	@Autowired
	StringRedisTemplate redis;

	private LoginAttemptLimiter limiter;

	private String ip;

	private String loginId;

	@BeforeEach
	void setUp() {
		limiter = limiter(5, 8);
		ip = newIp();
		loginId = "desk-" + UUID.randomUUID().toString().substring(0, 8);
	}

	@Test
	void 한도까지는_허용하고_다음_시도부터_막는다() {
		for (int i = 0; i < 5; i++) {
			assertThat(limiter.countAttempt(loginId, ip)).isEmpty();
		}

		Optional<Blocked> blocked = limiter.countAttempt(loginId, ip);

		assertThat(blocked).isPresent();
		assertThat(blocked.get().scope()).isEqualTo(Scope.LOGIN_ID_AND_IP);
		assertThat(blocked.get().retryAfter()).isPositive().isLessThanOrEqualTo(WINDOW);
	}

	@Test
	void 아이디가_달라도_같은_IP의_시도가_한도를_넘으면_막는다() {
		for (int i = 0; i < 8; i++) {
			assertThat(limiter.countAttempt("other-" + i, ip)).isEmpty();
		}

		assertThat(limiter.countAttempt(loginId, ip)).map(Blocked::scope).contains(Scope.IP);
	}

	// 누군가 밖에서 일부러 틀려도 학원 PC에서 쓰는 계정은 잠기지 않아야 한다.
	@Test
	void 다른_IP의_실패는_이_IP의_같은_아이디를_막지_않는다() {
		String attackerIp = newIp();
		for (int i = 0; i < 5; i++) {
			limiter.countAttempt(loginId, attackerIp);
		}
		assertThat(limiter.countAttempt(loginId, attackerIp)).isPresent();

		assertThat(limiter.countAttempt(loginId, ip)).isEmpty();
	}

	// 막힌 시도까지 세면, 잠긴 계정으로 로그인 버튼을 계속 누르는 한 사람 때문에 같은 IP의 모두가 막힌다.
	@Test
	void 막힌_시도는_어느_횟수에도_더하지_않는다() {
		for (int i = 0; i < 5; i++) {
			limiter.countAttempt(loginId, ip);
		}
		for (int i = 0; i < 20; i++) {
			assertThat(limiter.countAttempt(loginId, ip)).isPresent();
		}

		assertThat(redis.opsForValue().get(idKey(ip))).isEqualTo("5");
		assertThat(redis.opsForValue().get(ipKey(ip))).isEqualTo("5");
		assertThat(limiter.countAttempt("another-account", ip)).isEmpty();
	}

	// 막힌 IP가 아이디를 바꿔 가며 보낼 때마다 키가 생기면, 세션과 같이 쓰는 Redis를 채울 수 있다.
	@Test
	void IP가_막힌_뒤에는_아이디를_바꿔도_키가_늘지_않는다() {
		for (int i = 0; i < 8; i++) {
			limiter.countAttempt("other-" + i, ip);
		}
		int keysWhenBlocked = keysFor(ip).size();

		for (int i = 0; i < 20; i++) {
			assertThat(limiter.countAttempt("more-" + i, ip)).isPresent();
		}

		assertThat(keysFor(ip)).hasSize(keysWhenBlocked);
		assertThat(redis.opsForValue().get(ipKey(ip))).isEqualTo("8");
	}

	@Test
	void 성공하면_아이디와_IP_횟수를_지우고_IP_횟수를_되돌린다() {
		for (int i = 0; i < 5; i++) {
			limiter.countAttempt(loginId, ip);
		}
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

	// 횟수를 되돌리면서 만료 시간이 사라지면 그 IP의 횟수가 영원히 쌓인다.
	@Test
	void 성공_처리_뒤에도_IP_키의_만료_시간이_남는다() {
		limiter.countAttempt(loginId, ip);
		limiter.countAttempt(loginId, ip);
		limiter.loginSucceeded(loginId, ip);

		assertThat(keysFor(ip)).containsExactly(ipKey(ip));
		assertThat(redis.opsForValue().get(ipKey(ip))).isEqualTo("1");
		assertThat(redis.getExpire(ipKey(ip), TimeUnit.SECONDS)).isBetween(1L, WINDOW.toSeconds());
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
		limiter = limiter(1, 8);
		limiter.countAttempt(loginId, ip);
		redis.expire(idKey(ip), Duration.ofSeconds(30));

		assertThat(limiter.countAttempt(loginId, ip).orElseThrow().retryAfter())
				.isLessThanOrEqualTo(Duration.ofSeconds(30));
		assertThat(redis.getExpire(idKey(ip), TimeUnit.SECONDS)).isLessThanOrEqualTo(30L);
	}

	// 짧은 쪽을 알려 주면 프론트가 그 시간에 다시 시도해서 또 막힌다.
	@Test
	void 두_한도를_모두_넘었으면_더_늦게_풀리는_쪽의_시간을_돌려준다() {
		limiter = limiter(1, 1);
		limiter.countAttempt(loginId, ip);
		redis.expire(idKey(ip), Duration.ofSeconds(30));

		Blocked ipIsLater = limiter.countAttempt(loginId, ip).orElseThrow();
		assertThat(ipIsLater.scope()).isEqualTo(Scope.IP);
		assertThat(ipIsLater.retryAfter()).isGreaterThan(Duration.ofSeconds(30));

		redis.expire(idKey(ip), WINDOW);
		redis.expire(ipKey(ip), Duration.ofSeconds(30));

		Blocked idIsLater = limiter.countAttempt(loginId, ip).orElseThrow();
		assertThat(idIsLater.scope()).isEqualTo(Scope.LOGIN_ID_AND_IP);
		assertThat(idIsLater.retryAfter()).isGreaterThan(Duration.ofSeconds(30));
	}

	// 이 코드는 만료 시간 없는 키를 만들지 않는다. 누가 Redis를 직접 건드려 생겼다면 영원히 잠기지 않게 고치고 흔적을 남긴다.
	@Test
	void 만료_시간이_없는_키를_만나면_만료_시간을_다시_건다(CapturedOutput output) {
		limiter = limiter(1, 8);
		limiter.countAttempt(loginId, ip);
		String idKey = idKey(ip);
		redis.persist(idKey);

		Blocked blocked = limiter.countAttempt(loginId, ip).orElseThrow();

		assertThat(blocked.retryAfter()).isEqualTo(WINDOW);
		assertThat(redis.getExpire(idKey, TimeUnit.SECONDS)).isBetween(1L, WINDOW.toSeconds());
		assertThat(output).contains("만료 시간이 없는 로그인 시도 키를 고쳤다").contains(idKey).doesNotContain(loginId);
	}

	@Test
	void 대소문자와_공백이_달라도_같은_아이디로_센다() {
		limiter = limiter(2, 8);
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

	// 비밀키 없는 해시는 Redis 내용이 새면 흔한 값을 대입해서 원문을 찾을 수 있다.
	@Test
	void 아이디는_비밀키를_섞어_키로_만든다() throws Exception {
		limiter.countAttempt(loginId, ip);
		String keyWithFirstSecret = idKey(ip);
		String sha256 = HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(loginId.getBytes(StandardCharsets.UTF_8)), 0, 16);
		assertThat(keyWithFirstSecret).doesNotContain(sha256);

		// 비밀키가 다르면 같은 아이디도 다른 키가 된다.
		redis.delete(keysFor(ip));
		limiter(5, 8, "another-login-attempt-hmac-key-9876543210").countAttempt(loginId, ip);

		assertThat(idKey(ip)).isNotEqualTo(keyWithFirstSecret);
	}

	// IPv6 가입자는 보통 /64 대역을 통째로 받는다. 주소 전체로 세면 기기 한 대가 주소를 바꿔 가며 한도를 계속 새로 받는다.
	@Test
	void IPv6는_같은_64비트_대역을_한_곳으로_센다() {
		String prefix = newIpv6Prefix();
		for (int i = 0; i < 5; i++) {
			assertThat(limiter.countAttempt(loginId, prefix + ":0:0:0:" + Integer.toHexString(i + 1))).isEmpty();
		}

		assertThat(limiter.countAttempt(loginId, prefix + ":ffff:ffff:ffff:ffff")).isPresent();
		assertThat(limiter.countAttempt(loginId, newIpv6Prefix() + "::1")).isEmpty();
	}

	@Test
	void 키에_넣는_주소() {
		assertThat(LoginAttemptLimiter.ipKeyPart("203.0.113.7")).isEqualTo("203.0.113.7");
		assertThat(LoginAttemptLimiter.ipKeyPart("2001:db8:12:3400:1:2:3:4")).isEqualTo("2001:0db8:0012:3400::/64");
		assertThat(LoginAttemptLimiter.ipKeyPart("2001:DB8:12:3400::ffff")).isEqualTo("2001:0db8:0012:3400::/64");
		assertThat(LoginAttemptLimiter.ipKeyPart("0:0:0:0:0:0:0:1")).isEqualTo("0000:0000:0000:0000::/64");
		// IPv4를 IPv6로 감싼 표기는 IPv4와 같은 곳이다.
		assertThat(LoginAttemptLimiter.ipKeyPart("::ffff:203.0.113.7")).isEqualTo("203.0.113.7");
		assertThat(LoginAttemptLimiter.ipKeyPart(null)).isEqualTo("unknown");
		assertThat(LoginAttemptLimiter.ipKeyPart(" ")).isEqualTo("unknown");
		// 주소로 읽을 수 없는 값도 세기는 한다. 키에는 원문 대신 해시가 들어간다.
		assertThat(LoginAttemptLimiter.ipKeyPart("not:an:address")).matches("invalid-[0-9a-f]+");
	}

	// 비밀번호 해시 계산은 느리다. "확인 후 증가"로 만들면 동시에 보낸 요청이 모두 확인을 통과한다.
	@Test
	void 동시에_보내도_아이디_한도만큼만_허용된다() throws Exception {
		limiter = limiter(5, 1000);

		assertThat(allowedOutOfConcurrent(50, i -> loginId)).isEqualTo(5);
	}

	@Test
	void 동시에_아이디를_바꿔_가며_보내도_IP_한도만큼만_허용된다() throws Exception {
		assertThat(allowedOutOfConcurrent(50, i -> "guess-" + i)).isEqualTo(8);
	}

	private int allowedOutOfConcurrent(int requests, IntFunction<String> loginIdOf) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(requests);
		CountDownLatch ready = new CountDownLatch(requests);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(requests);
		AtomicInteger allowed = new AtomicInteger();
		AtomicInteger failed = new AtomicInteger();
		try {
			for (int i = 0; i < requests; i++) {
				String attemptLoginId = loginIdOf.apply(i);
				executor.submit(() -> {
					ready.countDown();
					try {
						start.await();
						if (limiter.countAttempt(attemptLoginId, ip).isEmpty()) {
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
		return allowed.get();
	}

	private LoginAttemptLimiter limiter(int maxFailuresPerLoginIdAndIp, int maxFailuresPerIp) {
		return limiter(maxFailuresPerLoginIdAndIp, maxFailuresPerIp, HMAC_KEY);
	}

	private LoginAttemptLimiter limiter(int maxFailuresPerLoginIdAndIp, int maxFailuresPerIp, String hmacKey) {
		return new LoginAttemptLimiter(redis,
				new LoginAttemptProperties(WINDOW, maxFailuresPerLoginIdAndIp, maxFailuresPerIp, hmacKey));
	}

	private Set<String> keysFor(String ip) {
		return redis.keys(KEY_PREFIX + "*" + ip);
	}

	private String idKey(String ip) {
		return keysFor(ip).stream().filter(key -> key.contains(":id:")).findFirst().orElseThrow();
	}

	private static String ipKey(String ip) {
		return KEY_PREFIX + "ip:" + ip;
	}

	// 문서용으로 예약된 대역(198.51.100.0/24)에서 테스트마다 다른 주소를 쓴다.
	private static String newIp() {
		int n = IP_SEQUENCE.incrementAndGet();
		return "198.51." + (100 + n / 250) + "." + (n % 250 + 1);
	}

	// 문서용으로 예약된 대역(2001:db8::/32)에서 테스트마다 다른 /64를 쓴다.
	private static String newIpv6Prefix() {
		return "2001:db8:0:" + Integer.toHexString(IP_SEQUENCE.incrementAndGet());
	}

}
