package com.jey.core.shared;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UuidV7Test {

	private static final Instant NOW = Instant.parse("2026-11-03T01:21:05.123Z");

	@Test
	void 버전_7_UUID를_만든다() {
		UUID uuid = UuidV7.generate(Clock.fixed(NOW, ZoneOffset.UTC));

		assertThat(uuid.version()).isEqualTo(7);
		assertThat(uuid.variant()).isEqualTo(2);
	}

	@Test
	void 앞_48비트는_만든_시각의_밀리초다() {
		UUID uuid = UuidV7.generate(Clock.fixed(NOW, ZoneOffset.UTC));

		assertThat(uuid.getMostSignificantBits() >>> 16).isEqualTo(NOW.toEpochMilli());
	}

	// 문자열 순서가 곧 시간 순서라, 발행 기록이나 로그를 ID로 정렬하면 발생 순서가 된다.
	@Test
	void 나중에_만든_UUID가_문자열_순서로_뒤에_온다() {
		UUID earlier = UuidV7.generate(Clock.fixed(NOW, ZoneOffset.UTC));
		UUID later = UuidV7.generate(Clock.fixed(NOW.plusMillis(1), ZoneOffset.UTC));

		assertThat(later.toString()).isGreaterThan(earlier.toString());
	}

	@Test
	void 같은_시각에_만들어도_겹치지_않는다() {
		Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
		Set<UUID> uuids = new HashSet<>();
		for (int i = 0; i < 10_000; i++) {
			uuids.add(UuidV7.generate(clock));
		}

		assertThat(uuids).hasSize(10_000);
	}

}
