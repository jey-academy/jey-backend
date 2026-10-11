package com.jey.core.auth;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.jey.TestcontainersConfiguration;
import com.jey.core.auth.api.AccountRateLimiter;
import com.jey.core.auth.api.AccountRateLimiter.Permit;
import com.jey.core.auth.api.RateLimit;
import com.jey.core.shared.Actor;
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

	private Actor.User account;

	// 막힌 것을 알리라고 불린 횟수만큼 쌓인다.
	private final List<RateLimitedException> notices = new CopyOnWriteArrayList<>();

	@BeforeEach
	void setUp() {
		account = newAccount();
	}

	@Test
	void 한도까지는_통과하고_그다음부터_막는다() {
		for (int i = 0; i < 5; i++) {
			assertThat(pass(LIMIT, account).limit()).isEqualTo(LIMIT);
		}

		RateLimitedException blocked = blocked(LIMIT, account);

		assertThat(blocked.getErrorCode()).isEqualTo(TestLimitCode.TEST_ACCOUNT_LIMITED);
		assertThat(blocked.getRetryAfter()).isPositive().isLessThanOrEqualTo(WINDOW);
	}

	// 막힌 요청까지 세면 계속 누르는 동안 횟수가 끝없이 는다.
	@Test
	void 막힌_요청은_세지_않는다() {
		passTimes(LIMIT, account, 5);

		for (int i = 0; i < 20; i++) {
			blocked(LIMIT, account);
		}

		assertThat(count(LIMIT, account)).isEqualTo("5");
	}

	// 고정 구간이다. 처음 센 때부터 window가 지나면 풀린다. 그 뒤에 센 것이 만료를 미루지 않는다.
	@Test
	void 만료_시간은_처음_셀_때만_정한다() {
		pass(LIMIT, account);
		assertThat(redis.getExpire(key(LIMIT, account), TimeUnit.SECONDS)).isPositive()
				.isLessThanOrEqualTo(WINDOW.toSeconds());
		redis.expire(key(LIMIT, account), Duration.ofMinutes(3));

		pass(LIMIT, account);

		assertThat(redis.getExpire(key(LIMIT, account), TimeUnit.SECONDS)).isLessThanOrEqualTo(180);
	}

	// 화면은 이 값으로 "몇 분 뒤에 다시"를 보여 준다. 구간의 길이가 아니라 구간이 끝날 때까지 남은 시간이어야 한다.
	@Test
	void 막힌_요청에는_구간이_끝날_때까지_남은_시간을_알린다() {
		passTimes(LIMIT, account, 5);
		redis.expire(key(LIMIT, account), Duration.ofMinutes(3));

		assertThat(blocked(LIMIT, account).getRetryAfter()).isBetween(Duration.ofSeconds(170),
				Duration.ofSeconds(180));
	}

	@Test
	void 구간이_지나면_풀린다() throws InterruptedException {
		passTimes(LIMIT, account, 5);
		blocked(LIMIT, account);

		endWindow(LIMIT, account);

		pass(LIMIT, account);
		assertThat(count(LIMIT, account)).isEqualTo("1");
	}

	@Test
	void 되돌리면_한_번을_다시_쓸_수_있다() {
		passTimes(LIMIT, account, 4);
		Permit last = pass(LIMIT, account);

		limiter.refund(last);

		pass(LIMIT, account);
		blocked(LIMIT, account);
	}

	@Test
	void 되돌려도_0보다_작아지지_않고_만료_시간은_그대로다() {
		Permit permit = pass(LIMIT, account);

		limiter.refund(permit);
		limiter.refund(permit);

		assertThat(count(LIMIT, account)).isEqualTo("0");
		assertThat(redis.getExpire(key(LIMIT, account), TimeUnit.SECONDS)).isPositive();
	}

	// 구간이 끝나기 직전에 센 요청이 구간이 바뀐 뒤에 끝나는 경우다.
	// 이때 줄이면 새 구간에서 센 것이 지워져서, 경계에 맞춰 보낸 요청으로 한도를 깎을 수 있다.
	@Test
	void 구간이_바뀐_뒤에_되돌리면_새_구간의_횟수를_건드리지_않는다() throws InterruptedException {
		Permit old = pass(LIMIT, account);
		endWindow(LIMIT, account);
		passTimes(LIMIT, account, 2);

		limiter.refund(old);

		assertThat(count(LIMIT, account)).isEqualTo("2");
	}

	// 없는 키를 줄이면 만료 시간 없는 키가 생기고, 그 키는 영영 남는다.
	@Test
	void 구간이_끝난_뒤에_되돌려도_키가_생기지_않는다() throws InterruptedException {
		Permit old = pass(LIMIT, account);
		endWindow(LIMIT, account);

		limiter.refund(old);

		assertThat(redis.hasKey(key(LIMIT, account))).isFalse();
	}

	@Test
	void 같은_구간에서_센_것은_표식이_같고_구간이_바뀌면_달라진다() throws InterruptedException {
		Permit first = pass(LIMIT, account);
		Permit second = pass(LIMIT, account);
		endWindow(LIMIT, account);
		Permit next = pass(LIMIT, account);

		assertThat(second.window()).isEqualTo(first.window());
		assertThat(next.window()).isNotEqualTo(first.window());
	}

	@Test
	void 계정이_다르면_따로_센다() {
		passTimes(LIMIT, account, 5);

		pass(LIMIT, newAccount());
	}

	@Test
	void 이름이_다르면_따로_센다() {
		passTimes(LIMIT, account, 5);

		pass(new RateLimit("test.other-thing", 5, WINDOW), account);
	}

	// 확인과 증가가 한 번에 되지 않으면 동시에 온 요청이 모두 확인을 통과한다.
	@Test
	void 동시에_요청해도_한도만큼만_통과하고_막힌_것은_한_번만_알린다() throws Exception {
		int threads = 24;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<Boolean>> results = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				results.add(pool.submit(() -> {
					ready.countDown();
					start.await();
					try {
						pass(LIMIT, account);
						return true;
					}
					catch (RateLimitedException ex) {
						return false;
					}
				}));
			}
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();

			int passed = 0;
			for (Future<Boolean> result : results) {
				if (result.get(10, TimeUnit.SECONDS)) {
					passed++;
				}
			}

			assertThat(passed).isEqualTo(5);
			assertThat(notices).hasSize(1);
		}
		finally {
			pool.shutdownNow();
		}
	}

	// 막힌 계정이 계속 보내면 기록이 끝없이 는다. 구간마다 한 번만 알린다.
	@Test
	void 한_구간에서_막힌_것은_처음_한_번만_알린다() {
		passTimes(LIMIT, account, 4);
		Permit last = pass(LIMIT, account);

		RateLimitedException first = blocked(LIMIT, account);
		blocked(LIMIT, account);
		// 되돌려서 풀렸다가 다시 막혀도 같은 구간이면 다시 알리지 않는다.
		limiter.refund(last);
		pass(LIMIT, account);
		blocked(LIMIT, account);

		assertThat(notices).containsExactly(first);
	}

	@Test
	void 구간이_바뀌면_다시_알린다() throws InterruptedException {
		passTimes(LIMIT, account, 5);
		blocked(LIMIT, account);

		endWindow(LIMIT, account);
		passTimes(LIMIT, account, 5);
		blocked(LIMIT, account);

		assertThat(notices).hasSize(2);
	}

	// 알리는 일(감사 로그 쓰기)이 실패하면 "요청이 너무 많다" 대신 그 실패가 나간다.
	// 표시가 남아 있으면 이 구간에서는 막힌 사실이 영영 적히지 않으므로, 다음에 막힌 요청이 다시 알린다.
	@Test
	void 알리다_실패하면_그_실패가_나가고_다음에_막힌_요청이_다시_알린다(CapturedOutput output) {
		passTimes(LIMIT, account, 5);
		IllegalStateException failure = new IllegalStateException("감사 로그를 쓰지 못했다");

		assertThatThrownBy(() -> limiter.acquire(LIMIT, account, TestLimitCode.TEST_ACCOUNT_LIMITED, denial -> {
			throw failure;
		})).isSameAs(failure);

		assertThat(output.getOut()).contains("한도에 막힌 사실을 알리지 못했다")
				.contains("limit=test.limited-thing accountId=" + account.accountId());
		blocked(LIMIT, account);
		blocked(LIMIT, account);
		assertThat(notices).hasSize(1);
	}

	// 알리던 서버가 죽으면 "알리는 중" 표시만 남는다. 알리는 일에 준 시간이 지나면 다음에 막힌 요청이 다시 알린다.
	@Test
	void 알리다_만_표시는_시간이_지나면_다시_알린다() {
		passTimes(LIMIT, account, 5);
		// 알리는 중이고 아직 시간이 남았다. 다른 요청이 알리고 있는 것이므로 겹쳐 알리지 않는다.
		redis.opsForHash().put(key(LIMIT, account), "noticed", "9999999999999");
		blocked(LIMIT, account);
		assertThat(notices).isEmpty();

		// 시간이 지났다. 알리던 쪽이 끝내지 못한 것이다.
		redis.opsForHash().put(key(LIMIT, account), "noticed", "1");
		blocked(LIMIT, account);
		blocked(LIMIT, account);

		assertThat(notices).hasSize(1);
		assertThat(redis.opsForHash().get(key(LIMIT, account), "noticed")).isEqualTo("done");
	}

	// 만료 시간이 없는 키는 영원히 잠근다. 이 장치는 그런 키를 만들지 않지만 밖에서 생겼다면 고친다.
	@Test
	void 만료_시간이_없는_키는_고치고_알린다(CapturedOutput output) {
		redis.opsForHash().put(key(LIMIT, account), "count", "5");

		RateLimitedException blocked = blocked(LIMIT, account);

		assertThat(blocked.getRetryAfter()).isPositive().isLessThanOrEqualTo(WINDOW);
		assertThat(redis.getExpire(key(LIMIT, account), TimeUnit.SECONDS)).isPositive();
		assertThat(output.getOut()).contains("만료 시간이 없는 횟수 제한 키를 고쳤다");
	}

	// 429가 아닌 응답에 "잠시 후 다시"가 붙어 나가면 안 된다. 세기 전에 거절한다.
	@Test
	void 상태가_429가_아닌_에러_코드로는_셀_수_없다() {
		assertThatThrownBy(() -> limiter.acquire(LIMIT, account, CommonErrorCode.CONFLICT, notices::add))
				.isInstanceOf(IllegalArgumentException.class);

		assertThat(redis.hasKey(key(LIMIT, account))).isFalse();
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
	void 한도는_1_이상이고_구간은_1밀리초_이상_30일_이하다() {
		assertThatThrownBy(() -> new RateLimit("member.phone-reveal", 0, WINDOW))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RateLimit("member.phone-reveal", 5, Duration.ZERO))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RateLimit("member.phone-reveal", 5, Duration.ofMinutes(-1)))
				.isInstanceOf(IllegalArgumentException.class);
		// 밀리초로 바꾸면 0이 된다. 센 것이 바로 지워져 한도가 꺼진다.
		assertThatThrownBy(() -> new RateLimit("member.phone-reveal", 5, Duration.ofNanos(500)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RateLimit("member.phone-reveal", 5, Duration.ofDays(31)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RateLimit("member.phone-reveal", 5, null))
				.isInstanceOf(NullPointerException.class);
		assertThat(new RateLimit("member.phone-reveal", 1, Duration.ofMillis(1)).window()).isEqualTo(Duration.ofMillis(1));
		assertThat(new RateLimit("member.phone-reveal", 1, Duration.ofDays(30)).max()).isEqualTo(1);
	}

	private Permit pass(RateLimit limit, Actor.User who) {
		Consumer<RateLimitedException> notice = notices::add;
		return limiter.acquire(limit, who, TestLimitCode.TEST_ACCOUNT_LIMITED, notice);
	}

	private void passTimes(RateLimit limit, Actor.User who, int times) {
		for (int i = 0; i < times; i++) {
			pass(limit, who);
		}
	}

	private RateLimitedException blocked(RateLimit limit, Actor.User who) {
		RateLimitedException blocked = catchThrowableOfType(RateLimitedException.class, () -> pass(limit, who));
		assertThat(blocked).as("막혀야 한다").isNotNull();
		return blocked;
	}

	// 구간이 끝난 것처럼 만든다. 짧은 구간을 두고 기다리면 느린 실행에서 구간이 먼저 끝나 테스트가 흔들린다.
	private void endWindow(RateLimit limit, Actor.User who) throws InterruptedException {
		redis.expire(key(limit, who), Duration.ofMillis(1));
		for (int i = 0; i < 500 && Boolean.TRUE.equals(redis.hasKey(key(limit, who))); i++) {
			Thread.sleep(10);
		}
		assertThat(redis.hasKey(key(limit, who))).isFalse();
	}

	private String count(RateLimit limit, Actor.User who) {
		return (String) redis.opsForHash().get(key(limit, who), "count");
	}

	private static Actor.User newAccount() {
		return new Actor.User(ACCOUNT_SEQUENCE.incrementAndGet());
	}

	private static String key(RateLimit limit, Actor.User who) {
		return "jey:auth:account-limit:" + limit.name() + ":" + who.accountId();
	}

	enum TestLimitCode implements ErrorCode {

		TEST_ACCOUNT_LIMITED;

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
			return "너무 많이 요청했습니다.";
		}

	}

}
