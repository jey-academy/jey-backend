package com.jey.billing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import com.jey.billing.application.LedgerLock;
import com.jey.billing.application.LedgerLockUnavailable;

// 잠금 저장소(Redis)가 끊겼을 때. 잡기는 장애로 알리고(다시 시도), 풀기의 실패는 결과를 바꾸지 않는다(시간이 지나면 풀린다).
// Docker가 필요 없다. 실제 Redis로 보는 것은 RedisLedgerLockIntegrationTest다.
class RedisLedgerLockTest {

	@SuppressWarnings("unchecked")
	private final ValueOperations<String, String> values = mock(ValueOperations.class);

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	private final RedisLedgerLock lock = new RedisLedgerLock(redis);

	@Test
	void 잠금_저장소에_닿지_못하면_장애로_알린다() {
		when(redis.opsForValue()).thenReturn(values);
		when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
			.thenThrow(new RedisConnectionFailureException("끊김"));

		assertThatThrownBy(() -> lock.tryAcquire("sheet-1", Duration.ofMillis(10)))
			.isInstanceOf(LedgerLockUnavailable.class);
	}

	@Test
	@SuppressWarnings("unchecked")
	void 풀다가_저장소가_끊겨도_예외를_던지지_않는다() {
		when(redis.opsForValue()).thenReturn(values);
		when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
		when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
			.thenThrow(new RedisConnectionFailureException("끊김"));
		Optional<LedgerLock.Held> held = lock.tryAcquire("sheet-1", Duration.ofMillis(10));

		held.orElseThrow().close();
	}

	@Test
	@SuppressWarnings("unchecked")
	void 시간이_다할_때까지_남기기로_하면_닫아도_풀지_않는다() {
		when(redis.opsForValue()).thenReturn(values);
		when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
		LedgerLock.Held held = lock.tryAcquire("sheet-1", Duration.ofMillis(10)).orElseThrow();

		held.keepUntilExpiry();
		held.close();

		verify(redis, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
	}

	@Test
	void 다른_쪽이_쥐고_있으면_기다린_뒤_비어_있다() {
		when(redis.opsForValue()).thenReturn(values);
		when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

		assertThat(lock.tryAcquire("sheet-1", Duration.ofMillis(250))).isEmpty();
	}

}
