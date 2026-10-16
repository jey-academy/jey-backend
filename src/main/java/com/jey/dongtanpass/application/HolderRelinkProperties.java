package com.jey.dongtanpass.application;

import java.time.Duration;

import com.jey.core.auth.api.RateLimit;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 학부모 링크 다시 받기의 틀린 시도 한도({@code jey.dongtanpass.holder-relink.*}, ADR-0035). 값이 없거나 잘못되면 서버가 뜨지 않는다.
 *
 * @param maxFailuresPerPhoneAndClient 한 접속 주소가 한 번호로 구간 안에 틀릴 수 있는 횟수
 * @param maxFailuresPerClient 한 접속 주소가 번호와 무관하게 구간 안에 틀릴 수 있는 횟수.
 * 학원 와이파이처럼 여러 사람이 주소 하나를 함께 쓰므로 넉넉하게 둔다
 * @param window 세는 구간. 처음 틀린 때부터 이 시간이 지나면 횟수가 지워진다(두 기준이 같은 길이를 쓴다)
 */
@ConfigurationProperties("jey.dongtanpass.holder-relink")
record HolderRelinkProperties(int maxFailuresPerPhoneAndClient, int maxFailuresPerClient, Duration window) {

	private static final String PREFIX = "jey.dongtanpass.holder-relink.";

	// 단위를 빼먹은 "5"는 5밀리초로 읽힌다. 그런 값으로 뜨면 제한이 사실상 꺼진다.
	private static final Duration MIN_WINDOW = Duration.ofMinutes(1);

	HolderRelinkProperties {
		if (window == null || window.compareTo(MIN_WINDOW) < 0) {
			throw new IllegalArgumentException(PREFIX + "window는 1분 이상이어야 한다(단위를 붙였는지 확인): " + window);
		}
		if (maxFailuresPerPhoneAndClient < 1) {
			throw new IllegalArgumentException(
					PREFIX + "max-failures-per-phone-and-client는 1 이상이어야 한다: " + maxFailuresPerPhoneAndClient);
		}
		if (maxFailuresPerClient < 1) {
			throw new IllegalArgumentException(
					PREFIX + "max-failures-per-client는 1 이상이어야 한다: " + maxFailuresPerClient);
		}
	}

	RateLimit perPhoneAndClient() {
		return RateLimit.of(DongtanpassRateLimit.HOLDER_RELINK, maxFailuresPerPhoneAndClient, window);
	}

	RateLimit perClient() {
		return RateLimit.of(DongtanpassRateLimit.HOLDER_RELINK_CLIENT, maxFailuresPerClient, window);
	}

}
