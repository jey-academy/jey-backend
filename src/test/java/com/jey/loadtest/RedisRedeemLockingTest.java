package com.jey.loadtest;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
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
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 비교 실험 D의 분산 락이 실제 Redis에서 정한 대로 잡히고 풀리는지 본다(다른 테스트와 같은 설정이라 컨텍스트를 새로 만들지 않는다).
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

}
