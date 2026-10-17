package com.jey.dongtanpass.infrastructure;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import com.jey.TestcontainersConfiguration;
import com.jey.dongtanpass.application.PassQrCodes;
import com.jey.dongtanpass.application.QrCode;
import com.jey.dongtanpass.application.UsedQrTokens;
import com.jey.dongtanpass.application.VerifiedQr;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 Redis에서 확인한다. 토큰은 매번 새로 만들어 토큰 ID가 테스트끼리 겹치지 않는다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class RedisUsedQrTokensTest {

	private static final Instant NOW = Instant.parse("2026-10-17T01:00:00Z");

	@Autowired
	UsedQrTokens usedQrTokens;

	@Autowired
	PassQrCodes passQrCodes;

	@Autowired
	StringRedisTemplate redis;

	@Test
	void 처음_기록하면_참이고_같은_토큰을_다시_기록하면_거짓이다() {
		VerifiedQr qr = verified(7L);

		assertThat(usedQrTokens.markUsed(qr, NOW)).isTrue();
		assertThat(usedQrTokens.markUsed(qr, NOW)).isFalse();
	}

	@Test
	void 다른_토큰은_서로_영향이_없다() {
		VerifiedQr first = verified(7L);
		VerifiedQr second = verified(7L);

		assertThat(usedQrTokens.markUsed(first, NOW)).isTrue();
		assertThat(usedQrTokens.markUsed(second, NOW)).isTrue();
	}

	// 토큰이 무효가 되는 때까지만 기억하면 된다. 그 뒤에는 서명 확인에서 이미 걸러진다.
	@Test
	void 남은_수명을_초로_올려_기록한다() {
		VerifiedQr qr = verified(7L);

		usedQrTokens.markUsed(qr, qr.expiresAt().minusMillis(30_200));

		assertThat(ttlMillis(qr)).isGreaterThan(30_200).isLessThanOrEqualTo(31_000);
	}

	@Test
	void 거의_끝난_토큰도_1초는_기록한다() {
		VerifiedQr qr = verified(7L);

		usedQrTokens.markUsed(qr, qr.expiresAt().minusMillis(300));

		assertThat(ttlMillis(qr)).isGreaterThan(0).isLessThanOrEqualTo(1_000);
		assertThat(usedQrTokens.markUsed(qr, qr.expiresAt().minusMillis(300))).isFalse();
	}

	// 확인한 뒤 적기까지 시계가 만료 시각에 닿아도 적는다(남은 수명 0은 Redis가 받지 않는다).
	@Test
	void 만료_시각에_적어도_1초는_기록한다() {
		VerifiedQr qr = verified(7L);

		assertThat(usedQrTokens.markUsed(qr, qr.expiresAt())).isTrue();

		assertThat(ttlMillis(qr)).isGreaterThan(0).isLessThanOrEqualTo(1_000);
	}

	private VerifiedQr verified(long passId) {
		QrCode code = passQrCodes.issue(passId, NOW);
		return passQrCodes.verify(code.token(), NOW).orElseThrow();
	}

	private long ttlMillis(VerifiedQr qr) {
		Long ttl = redis.getExpire("jey:dongtanpass:qr-used:" + qr.tokenId(), TimeUnit.MILLISECONDS);
		assertThat(ttl).as("키가 있고 수명이 걸려 있다").isNotNull().isPositive();
		return ttl;
	}

}
