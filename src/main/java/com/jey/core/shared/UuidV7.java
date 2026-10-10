package com.jey.core.shared;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/**
 * 시간순으로 정렬되는 UUID(버전 7, RFC 9562)를 만든다. Java 21에는 버전 7 생성기가 없어 직접 만든다.
 * 앞 48비트가 밀리초 시각이라 ID 순서가 곧 발생 순서이고, DB 인덱스에서도 뒤쪽에만 쌓인다.
 * 같은 밀리초 안에서의 순서는 보장하지 않는다.
 */
final class UuidV7 {

	private static final SecureRandom RANDOM = new SecureRandom();

	private UuidV7() {
	}

	static UUID generate(Clock clock) {
		// 시각 48비트 | 버전 4비트(0111) | 난수 12비트
		long mostSignificantBits = (clock.millis() << 16) | 0x7000L | RANDOM.nextInt(1 << 12);
		// 변형 2비트(10) | 난수 62비트
		long leastSignificantBits = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
		return new UUID(mostSignificantBits, leastSignificantBits);
	}

}
