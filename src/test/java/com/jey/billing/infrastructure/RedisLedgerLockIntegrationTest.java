package com.jey.billing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.jey.billing.LedgerSheetsTestSupport;
import com.jey.billing.application.LedgerLock;

// 실제 Redis에서의 장부 잠금. 같은 장부는 한 쪽만 쥐고, 시간이 지나 남이 잡은 잠금은 지우지 않는다.
class RedisLedgerLockIntegrationTest extends LedgerSheetsTestSupport {

	@Autowired
	LedgerLock lock;

	@Test
	void 쥐는_동안_다른_쪽은_잡지_못하고_풀면_잡는다() {
		LedgerLock.Held first = lock.tryAcquire(SHEET_ID, Duration.ZERO).orElseThrow();
		assertThat(redis.getExpire(LOCK_KEY)).isBetween(1L, RedisLedgerLock.TTL.toSeconds());

		assertThat(lock.tryAcquire(SHEET_ID, Duration.ofMillis(200))).isEmpty();
		first.close();
		LedgerLock.Held second = lock.tryAcquire(SHEET_ID, Duration.ZERO).orElseThrow();
		second.close();
	}

	@Test
	void 시간이_지나_남이_잡은_잠금은_지우지_않는다() {
		LedgerLock.Held mine = lock.tryAcquire(SHEET_ID, Duration.ZERO).orElseThrow();
		redis.opsForValue().set(LOCK_KEY, "다른 쪽", Duration.ofMinutes(1));

		mine.close();

		assertThat(redis.opsForValue().get(LOCK_KEY)).isEqualTo("다른 쪽");
	}

}
