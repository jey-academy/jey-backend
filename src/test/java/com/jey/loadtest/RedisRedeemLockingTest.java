package com.jey.loadtest;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.jey.TestcontainersConfiguration;
import com.jey.core.shared.BusinessException;
import com.jey.dongtanpass.application.DongtanpassErrorCode;
import com.jey.dongtanpass.domain.PassRepository;
import com.jey.dongtanpass.domain.RedemptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 비교 실험의 redis 방식(분산 락)이 실제 Redis에서 정한 대로 잡히고 풀리는지 본다(다른 테스트와 같은 설정이라 컨텍스트를 새로 만들지 않는다).
// 패스 ID는 테스트마다 무작위다(Redis의 키는 테스트 사이에 남는다).
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class RedisRedeemLockingTest {

	@Autowired
	StringRedisTemplate redis;

	@Autowired
	PassRepository passes;

	@Autowired
	RedemptionRepository redemptions;

	private RedisRedeemLocking locking;

	private long passId;

	private String key;

	@BeforeEach
	void setUp() {
		locking = new RedisRedeemLocking(passes, redemptions, redis, Duration.ofSeconds(5));
		passId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
		key = RedisRedeemLocking.KEY_PREFIX + passId;
	}

	@Test
	void 잡는_동안_키와_만료_시간이_있고_끝나면_풀린다() {
		String result = locking.guard(passId, () -> {
			assertThat(redis.hasKey(key)).isTrue();
			assertThat(redis.getExpire(key)).isBetween(1L, 5L);
			return "done";
		});

		assertThat(result).isEqualTo("done");
		assertThat(redis.hasKey(key)).isFalse();
	}

	// 기다리지 않고 바로 "다시 시도"로 거른다. 걸러진 요청은 일을 하지 않는다.
	@Test
	void 잡힌_동안_같은_패스의_다른_요청은_바로_다시_시도다() {
		AtomicBoolean secondRan = new AtomicBoolean();

		locking.guard(passId, () -> {
			assertThatThrownBy(() -> locking.guard(passId, () -> {
				secondRan.set(true);
				return null;
			})).isInstanceOfSatisfying(BusinessException.class,
					ex -> assertThat(ex.getErrorCode()).isEqualTo(DongtanpassErrorCode.PASS_CHANGED_CONCURRENTLY));
			return null;
		});

		assertThat(secondRan).isFalse();
		assertThat(redis.hasKey(key)).isFalse();
	}

	@Test
	void 다른_패스는_따로_잡는다() {
		long other = passId + 1;

		String result = locking.guard(passId, () -> locking.guard(other, () -> "both"));

		assertThat(result).isEqualTo("both");
	}

	@Test
	void 일이_실패해도_풀린다() {
		assertThatThrownBy(() -> locking.guard(passId, () -> {
			throw new IllegalStateException("일이 실패했다");
		})).hasMessage("일이 실패했다");

		assertThat(redis.hasKey(key)).isFalse();
	}

	// 요청마다 다른 토큰을 쓴다. 만료로 풀린 뒤 같은 패스를 다른 요청이 잡았을 때, 먼저 끝난 요청의 해제가 그 락을 지우지 않는다.
	@Test
	void 만료_뒤_다른_요청이_잡은_락은_먼저_끝난_요청이_지우지_않는다() throws Exception {
		CountDownLatch otherHolds = new CountDownLatch(1);
		CountDownLatch firstDone = new CountDownLatch(1);
		Thread other = new Thread(() -> locking.guard(passId, () -> {
			otherHolds.countDown();
			try {
				firstDone.await(5, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			return null;
		}));

		locking.guard(passId, () -> {
			redis.delete(key);
			other.start();
			awaitQuietly(otherHolds);
			return null;
		});

		assertThat(redis.hasKey(key)).as("다른 요청의 락이 남아 있다").isTrue();
		firstDone.countDown();
		other.join(5000);
		assertThat(redis.hasKey(key)).isFalse();
	}

	// 일이 만료 시간보다 오래 걸려 락이 풀리고 다른 요청이 잡았다. 끝난 뒤의 해제가 그 요청의 락을 지우면 안 된다.
	@Test
	void 만료_뒤_남이_잡은_락은_지우지_않는다() {
		locking.guard(passId, () -> {
			redis.opsForValue().set(key, "someone-else", Duration.ofSeconds(5));
			return null;
		});

		assertThat(redis.opsForValue().get(key)).isEqualTo("someone-else");
		redis.delete(key);
	}

	// 잡았는지 알 수 없으면(파이프라인 등으로 응답이 비면) 통과시키지 않는다.
	@Test
	void 잡았는지_알_수_없으면_통과시키지_않는다() {
		StringRedisTemplate unknown = mock(StringRedisTemplate.class);
		@SuppressWarnings("unchecked")
		ValueOperations<String, String> values = mock(ValueOperations.class);
		when(unknown.opsForValue()).thenReturn(values);
		when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(null);
		RedisRedeemLocking blind = new RedisRedeemLocking(passes, redemptions, unknown, Duration.ofSeconds(5));
		AtomicBoolean ran = new AtomicBoolean();

		assertThatThrownBy(() -> blind.guard(passId, () -> {
			ran.set(true);
			return null;
		})).isInstanceOf(IllegalStateException.class);
		assertThat(ran).isFalse();
	}

	// 일은 끝났는데(커밋까지) 락을 풀다 Redis가 실패했다. 원래 결과를 돌려준다(500으로 바꾸지 않는다). 락은 만료로 풀린다.
	@Test
	void 풀다가_실패해도_원래_결과를_돌려준다() {
		StringRedisTemplate flaky = mock(StringRedisTemplate.class);
		@SuppressWarnings("unchecked")
		ValueOperations<String, String> values = mock(ValueOperations.class);
		when(flaky.opsForValue()).thenReturn(values);
		when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
		when(flaky.execute(any(RedisScript.class), anyList(), any(Object[].class)))
			.thenThrow(new IllegalStateException("Redis에 닿지 못했다"));
		RedisRedeemLocking locking = new RedisRedeemLocking(passes, redemptions, flaky, Duration.ofSeconds(5));

		assertThat(locking.guard(passId, () -> "committed")).isEqualTo("committed");
		assertThatThrownBy(() -> locking.guard(passId, () -> {
			throw new IllegalArgumentException("원래 예외");
		})).isInstanceOf(IllegalArgumentException.class).hasMessage("원래 예외");
	}

	private static void awaitQuietly(CountDownLatch latch) {
		try {
			latch.await(5, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
