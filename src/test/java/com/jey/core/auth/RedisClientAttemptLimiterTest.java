package com.jey.core.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.jey.TestcontainersConfiguration;
import com.jey.core.auth.api.ClientAttemptLimiter;
import com.jey.core.auth.api.ClientAttemptLimiter.Attempt;
import com.jey.core.auth.api.RateLimit;
import com.jey.core.shared.CommonErrorCode;
import com.jey.core.shared.ErrorCode;
import com.jey.core.shared.RateLimitedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

// 실제 Redis에서 확인한다. 테스트끼리 섞이지 않게 대상과 접속 주소를 매번 새로 만든다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class RedisClientAttemptLimiterTest {

	private static final Duration WINDOW = Duration.ofMinutes(5);

	private static final RateLimit PER_SUBJECT = new RateLimit("test.client-subject", 3, WINDOW);

	private static final RateLimit PER_CLIENT = new RateLimit("test.client-only", 6, WINDOW);

	@Autowired
	ClientAttemptLimiter limiter;

	@Autowired
	StringRedisTemplate redis;

	private String subject;

	private String address;

	// 막힌 것을 알리라고 불린 횟수만큼 쌓인다.
	private final List<RateLimitedException> notices = new CopyOnWriteArrayList<>();

	@BeforeEach
	void setUp() {
		subject = newSubject();
		address = newAddress();
	}

	@Test
	void 대상과_주소의_한도를_넘으면_막고_같은_주소의_다른_대상은_통과한다() {
		passTimes(subject, address, 3);

		RateLimitedException blocked = blocked(subject, address);

		assertThat(blocked.getErrorCode()).isEqualTo(TestLimitCode.TEST_CLIENT_LIMITED);
		pass(newSubject(), address);
		pass(subject, newAddress());
	}

	@Test
	void 주소의_한도를_넘으면_다른_대상도_막는다() {
		for (int i = 0; i < 6; i++) {
			pass(newSubject(), address);
		}

		blocked(newSubject(), address);
		pass(newSubject(), newAddress());
	}

	// 막힌 요청까지 세면 같은 주소의 다른 사람이 덩달아 막힌다.
	@Test
	void 막힌_요청은_어느_기준에도_세지_않는다() {
		passTimes(subject, address, 3);

		for (int i = 0; i < 10; i++) {
			blocked(subject, address);
		}

		assertThat(count(subjectKey(subject, address))).isEqualTo("3");
		assertThat(count(clientKey(address))).isEqualTo("3");
	}

	@Test
	void 되돌린_시도는_두_기준_모두에서_빠진다() {
		passTimes(subject, address, 2);
		Attempt last = pass(subject, address);

		limiter.refund(last);

		assertThat(count(subjectKey(subject, address))).isEqualTo("2");
		assertThat(count(clientKey(address))).isEqualTo("2");
		pass(subject, address);
		blocked(subject, address);
	}

	@Test
	void 구간이_바뀐_뒤에_되돌리면_새_구간의_횟수를_건드리지_않는다() {
		Attempt old = pass(subject, address);
		redis.delete(List.of(subjectKey(subject, address), clientKey(address)));
		pass(subject, address);

		limiter.refund(old);

		assertThat(count(subjectKey(subject, address))).isEqualTo("1");
		assertThat(count(clientKey(address))).isEqualTo("1");
	}

	@Test
	void 구간이_끝난_뒤에_되돌려도_키가_생기지_않는다() {
		Attempt old = pass(subject, address);
		redis.delete(List.of(subjectKey(subject, address), clientKey(address)));

		limiter.refund(old);

		assertThat(redis.hasKey(subjectKey(subject, address))).isFalse();
		assertThat(redis.hasKey(clientKey(address))).isFalse();
	}

	// 화면은 이 값으로 "몇 분 뒤에 다시"를 보여 준다.
	@Test
	void 막힌_요청에는_구간이_끝날_때까지_남은_시간을_알린다() {
		passTimes(subject, address, 3);
		redis.expire(subjectKey(subject, address), Duration.ofMinutes(3));

		assertThat(blocked(subject, address).getRetryAfter()).isBetween(Duration.ofSeconds(170),
				Duration.ofSeconds(180));
	}

	@Test
	void 두_기준에_모두_막히면_남은_시간이_긴_쪽으로_알린다() {
		for (int i = 0; i < 3; i++) {
			pass(subject, address);
			pass(newSubject(), address);
		}
		redis.expire(subjectKey(subject, address), Duration.ofMinutes(1));
		redis.expire(clientKey(address), Duration.ofMinutes(4));

		assertThat(blocked(subject, address).getRetryAfter()).isGreaterThan(Duration.ofMinutes(3));
	}

	// 고정 구간이다. 처음 센 때부터 구간이 지나면 풀린다. 그 뒤에 센 것이 만료를 미루지 않는다.
	@Test
	void 만료_시간은_처음_셀_때만_정한다() {
		pass(subject, address);
		redis.expire(subjectKey(subject, address), Duration.ofMinutes(2));

		pass(subject, address);

		assertThat(redis.getExpire(subjectKey(subject, address), TimeUnit.SECONDS)).isLessThanOrEqualTo(120);
		assertThat(redis.getExpire(clientKey(address), TimeUnit.SECONDS)).isPositive()
			.isLessThanOrEqualTo(WINDOW.toSeconds());
	}

	@Test
	void 한_구간에서_막힌_것은_기준마다_처음_한_번만_알린다() {
		passTimes(subject, address, 3);
		for (int i = 0; i < 5; i++) {
			blocked(subject, address);
		}
		assertThat(notices).hasSize(1);

		for (int i = 0; i < 3; i++) {
			pass(newSubject(), address);
		}
		for (int i = 0; i < 5; i++) {
			blocked(newSubject(), address);
		}

		assertThat(notices).hasSize(2);
	}

	@Test
	void 알리다_실패하면_그_실패가_나가고_다음에_막힌_요청이_다시_알린다() {
		passTimes(subject, address, 3);
		IllegalStateException failure = new IllegalStateException("기록 실패");

		assertThatThrownBy(() -> limiter.acquire(PER_SUBJECT, PER_CLIENT, subject, address,
				TestLimitCode.TEST_CLIENT_LIMITED, denial -> {
					throw failure;
				}))
			.isSameAs(failure);

		blocked(subject, address);
		assertThat(notices).hasSize(1);
	}

	// 알리던 서버가 죽으면 "알리는 중" 표시만 남는다. 알리는 일에 준 시간이 지나면 다음에 막힌 요청이 다시 알린다.
	@Test
	void 알리다_만_표시는_시간이_지나면_다시_알린다() {
		passTimes(subject, address, 3);
		// 알리는 중이고 아직 시간이 남았다. 다른 요청이 알리고 있는 것이므로 겹쳐 알리지 않는다.
		redis.opsForHash().put(subjectKey(subject, address), "noticed", "9999999999999");
		blocked(subject, address);
		assertThat(notices).isEmpty();

		// 시간이 지났다. 알리던 쪽이 끝내지 못한 것이다.
		redis.opsForHash().put(subjectKey(subject, address), "noticed", "1");
		blocked(subject, address);
		blocked(subject, address);

		assertThat(notices).hasSize(1);
		assertThat(redis.opsForHash().get(subjectKey(subject, address), "noticed")).isEqualTo("done");
	}

	// 만료 시간이 없는 키는 그 번호나 주소를 영원히 막는다. 이 장치는 그런 키를 만들지 않지만 밖에서 생겼다면(수동 조작, 복원) 고친다.
	@Test
	void 만료_시간이_없는_키는_고치고_알린다(CapturedOutput output) {
		redis.opsForHash().put(subjectKey(subject, address), "count", "3");
		redis.opsForHash().put(clientKey(address), "count", "1");

		RateLimitedException blocked = blocked(subject, address);

		assertThat(blocked.getRetryAfter()).isPositive().isLessThanOrEqualTo(WINDOW);
		assertThat(redis.getExpire(subjectKey(subject, address), TimeUnit.SECONDS)).isPositive()
			.isLessThanOrEqualTo(WINDOW.toSeconds());
		assertThat(redis.getExpire(clientKey(address), TimeUnit.SECONDS)).isPositive()
			.isLessThanOrEqualTo(WINDOW.toSeconds());
		assertThat(output.getOut()).contains("만료 시간이 없는 틀린 시도 키를 고쳤다")
			// 대상의 해시는 같은 번호를 로그끼리 이어 보게 한다. 한도 이름과 주소만 남긴다.
			.doesNotContain(subjectKey(subject, address).split(":")[4]);
	}

	// 동시에 보낸 요청이 "확인 후 증가" 사이로 빠져나가지 못한다.
	@Test
	void 동시에_요청해도_한도만큼만_통과하고_막힌_것은_한_번만_알린다() throws Exception {
		RateLimit perSubject = new RateLimit("test.client-subject", 10, WINDOW);
		RateLimit perClient = new RateLimit("test.client-only", 1000, WINDOW);
		int requests = 20;
		ExecutorService executor = Executors.newFixedThreadPool(requests);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Boolean>> results = new ArrayList<>();
		try {
			for (int i = 0; i < requests; i++) {
				results.add(executor.submit(() -> {
					start.await();
					try {
						limiter.acquire(perSubject, perClient, subject, address, TestLimitCode.TEST_CLIENT_LIMITED,
								notices::add);
						return true;
					}
					catch (RateLimitedException ex) {
						return false;
					}
				}));
			}
			start.countDown();
			int passed = 0;
			for (Future<Boolean> result : results) {
				if (result.get(30, TimeUnit.SECONDS)) {
					passed++;
				}
			}

			assertThat(passed).isEqualTo(10);
			assertThat(notices).hasSize(1);
			assertThat(count(clientKey(address))).isEqualTo("10");
		}
		finally {
			executor.shutdownNow();
		}
	}

	// 대상은 번호의 대조값 같은 것이지만, 원문이 그대로 키에 들어가지 않게 한 번 더 줄인다.
	@Test
	void 키에_대상_원문이_없다() {
		pass(subject, address);

		Set<String> keys = redis.keys("jey:auth:client-limit:*" + address);
		assertThat(keys).hasSize(2).allSatisfy(key -> assertThat(key).doesNotContain(subject));
	}

	// 센 한 번은 장치만 만든다. 키를 들고 다니는 값이라 밖에서 만든 것으로 남의 횟수를 줄이지 못하게 한다.
	@Test
	void 장치가_세지_않은_시도는_되돌릴_수_없다() {
		Attempt forged = new Attempt() {
		};

		assertThatThrownBy(() -> limiter.refund(forged)).isInstanceOf(IllegalArgumentException.class);
	}

	// 로그에 찍혀도 접속 주소와 대상의 해시가 남지 않는다.
	@Test
	void 센_한_번을_문자열로_바꿔도_키가_나오지_않는다() {
		Attempt attempt = pass(subject, address);

		assertThat(attempt.toString()).doesNotContain(address).doesNotContain("client-limit");
	}

	@Test
	void 상태가_429가_아닌_에러_코드로는_셀_수_없다() {
		assertThatThrownBy(() -> limiter.acquire(PER_SUBJECT, PER_CLIENT, subject, address,
				CommonErrorCode.BAD_REQUEST, notices::add))
			.isInstanceOf(IllegalArgumentException.class);

		assertThat(redis.hasKey(clientKey(address))).isFalse();
	}

	// 같은 이름이면 두 기준이 한 키를 함께 세게 된다.
	@Test
	void 두_한도의_이름이_같으면_셀_수_없다() {
		RateLimit same = new RateLimit("test.client-subject", 6, WINDOW);

		assertThatThrownBy(() -> limiter.acquire(PER_SUBJECT, same, subject, address,
				TestLimitCode.TEST_CLIENT_LIMITED, notices::add))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 대상이_비었으면_셀_수_없다() {
		assertThatThrownBy(() -> limiter.acquire(PER_SUBJECT, PER_CLIENT, " ", address,
				TestLimitCode.TEST_CLIENT_LIMITED, notices::add))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private Attempt pass(String who, String from) {
		return limiter.acquire(PER_SUBJECT, PER_CLIENT, who, from, TestLimitCode.TEST_CLIENT_LIMITED, notices::add);
	}

	private void passTimes(String who, String from, int times) {
		for (int i = 0; i < times; i++) {
			pass(who, from);
		}
	}

	private RateLimitedException blocked(String who, String from) {
		Consumer<RateLimitedException> notice = notices::add;
		RateLimitedException blocked = catchThrowableOfType(RateLimitedException.class,
				() -> limiter.acquire(PER_SUBJECT, PER_CLIENT, who, from, TestLimitCode.TEST_CLIENT_LIMITED, notice));
		assertThat(blocked).isNotNull();
		assertThat(blocked.getRetryAfter()).isPositive().isLessThanOrEqualTo(WINDOW);
		return blocked;
	}

	private String count(String key) {
		return (String) redis.opsForHash().get(key, "count");
	}

	// 대상은 SHA-256의 앞 16바이트(16진수)로 줄여 넣는다.
	private static String subjectKey(String who, String from) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(who.getBytes(StandardCharsets.UTF_8));
			return "jey:auth:client-limit:" + PER_SUBJECT.name() + ":" + HexFormat.of().formatHex(digest, 0, 16) + ":"
					+ from;
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private String clientKey(String from) {
		return "jey:auth:client-limit:" + PER_CLIENT.name() + ":" + from;
	}

	private static String newSubject() {
		return UUID.randomUUID().toString();
	}

	// 문서용 대역(203.0.113.0/24)과 겹치지 않게 사설 대역에서 고른다.
	private static String newAddress() {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		return "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(1, 255);
	}

	enum TestLimitCode implements ErrorCode {

		TEST_CLIENT_LIMITED;

		@Override
		public String code() {
			return name();
		}

		@Override
		public HttpStatus status() {
			return HttpStatus.TOO_MANY_REQUESTS;
		}

		@Override
		public String message() {
			return "너무 많이 틀렸습니다.";
		}

	}

}
