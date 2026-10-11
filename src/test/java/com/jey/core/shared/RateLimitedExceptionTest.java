package com.jey.core.shared;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitedExceptionTest {

	private static final ErrorCode LIMITED = new ErrorCode() {
		@Override
		public String code() {
			return "TEST_LIMITED";
		}

		@Override
		public HttpStatus status() {
			return HttpStatus.TOO_MANY_REQUESTS;
		}

		@Override
		public String message() {
			return "너무 많이 요청했습니다.";
		}
	};

	// 다른 상태의 응답에 "잠시 후 다시"만 붙어 나가는 것을 막는다.
	@Test
	void 상태가_429인_에러_코드만_받는다() {
		assertThatThrownBy(() -> new RateLimitedException(CommonErrorCode.CONFLICT, Duration.ofMinutes(1)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("429");
	}

	@Test
	void 남은_시간은_0보다_길어야_한다() {
		assertThatThrownBy(() -> new RateLimitedException(LIMITED, Duration.ZERO))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RateLimitedException(LIMITED, Duration.ofSeconds(-1)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RateLimitedException(LIMITED, null)).isInstanceOf(NullPointerException.class);
	}

	// 내려서 알리면 그 시각에 다시 보낸 요청이 또 막힌다.
	@Test
	void 헤더에_싣는_초는_올림하고_1초보다_작아지지_않는다() {
		assertThat(new RateLimitedException(LIMITED, Duration.ofMillis(61_200)).retryAfterSeconds()).isEqualTo(62);
		assertThat(new RateLimitedException(LIMITED, Duration.ofSeconds(60)).retryAfterSeconds()).isEqualTo(60);
		assertThat(new RateLimitedException(LIMITED, Duration.ofMillis(1)).retryAfterSeconds()).isEqualTo(1);
		assertThat(new RateLimitedException(LIMITED, Duration.ofNanos(10)).retryAfterSeconds()).isEqualTo(1);
		assertThat(new RateLimitedException(LIMITED, Duration.ofMinutes(15)).getErrorCode()).isSameAs(LIMITED);
	}

}
