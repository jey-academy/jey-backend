package com.jey.dongtanpass.infrastructure;

import java.time.Duration;
import java.time.Instant;

import com.jey.dongtanpass.application.VerifiedQr;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 적었는지 모르는 채로 통과시키지 않는다. 결과를 알 수 없는 응답(null)과 저장소 장애를 일부러 만들어야 해서 Spring 없이 만든다.
@ExtendWith(OutputCaptureExtension.class)
class RedisUsedQrTokensFailureTest {

	private static final Instant NOW = Instant.parse("2026-10-17T01:00:00Z");

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	@SuppressWarnings("unchecked")
	private final ValueOperations<String, String> values = mock(ValueOperations.class);

	private final VerifiedQr qr = mock(VerifiedQr.class);

	private final RedisUsedQrTokens usedQrTokens = new RedisUsedQrTokens(redis);

	@BeforeEach
	void setUp() {
		when(redis.opsForValue()).thenReturn(values);
		when(qr.passId()).thenReturn(11L);
		when(qr.tokenId()).thenReturn("0123456789abcdef0123456789abcdef");
		when(qr.expiresAt()).thenReturn(NOW.plusSeconds(60));
	}

	// 파이프라인·트랜잭션 안에서는 null이 온다. 처음으로 보고 통과시키면 재사용 막기가 통째로 꺼진다.
	@Test
	void 결과를_알_수_없으면_예외다() {
		when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(null);

		assertThatThrownBy(() -> usedQrTokens.markUsed(qr, NOW)).isInstanceOf(IllegalStateException.class);
	}

	// 어느 패스의 토큰이 "적었는지 모르는 상태"가 됐는지 로그로 이어 볼 수 있어야 한다. 토큰 ID는 남기지 않는다.
	@Test
	void 저장소에_닿지_못하면_패스_ID를_오류로_남기고_예외를_그대로_던진다(CapturedOutput output) {
		RedisConnectionFailureException failure = new RedisConnectionFailureException("닿지 못했다");
		when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenThrow(failure);

		assertThatThrownBy(() -> usedQrTokens.markUsed(qr, NOW)).isSameAs(failure);
		assertThat(output.getAll().lines().filter(line -> line.contains("쓴 QR 토큰을 적지 못했다")))
			.singleElement(STRING)
			.contains("ERROR")
			.contains("passId=11")
			.doesNotContain("0123456789abcdef0123456789abcdef");
	}

	// 대조군: 같은 준비에서 처음 적으면 참이다.
	@Test
	void 처음_적으면_참이다() {
		when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

		assertThat(usedQrTokens.markUsed(qr, NOW)).isTrue();
	}

}
