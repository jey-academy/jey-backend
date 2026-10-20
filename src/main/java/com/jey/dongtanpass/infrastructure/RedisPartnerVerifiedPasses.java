package com.jey.dongtanpass.infrastructure;

import com.jey.dongtanpass.application.PartnerVerifiedPasses;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 연계 학원이 확인한 패스를 Redis에 {@link PartnerVerifiedPasses#TTL} 동안 둔다. 값은 없다(키만 본다).
 * 키에는 ID만 넣는다(이름·번호 없음).
 */
@Component
class RedisPartnerVerifiedPasses implements PartnerVerifiedPasses {

	private static final String KEY_PREFIX = "jey:dongtanpass:partner-verified:";

	private final StringRedisTemplate redis;

	RedisPartnerVerifiedPasses(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public void remember(long partnerId, long passId) {
		redis.opsForValue().set(key(partnerId, passId), "1", TTL);
	}

	@Override
	public boolean isVerified(long partnerId, long passId) {
		Boolean exists = redis.hasKey(key(partnerId, passId));
		// 파이프라인·트랜잭션 안에서는 null이 온다. 확인했는지 모르는 채로 통과시키지 않는다.
		if (exists == null) {
			throw new IllegalStateException("연계 학원이 확인한 패스인지 알 수 없다");
		}
		return exists;
	}

	private static String key(long partnerId, long passId) {
		return KEY_PREFIX + partnerId + ":" + passId;
	}

}
