package com.jey.dongtanpass.application;

import java.time.Duration;

import com.jey.core.auth.api.RateLimit;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 데스크의 본인 미리 확인 한도({@code jey.dongtanpass.holder-check.*}). 계정마다 맞든 틀리든 센다(ADR-0033).
 * 값이 없거나 잘못되면 서버가 뜨지 않는다.
 *
 * @param max 한 계정이 구간 안에 이름과 학부모 번호를 패스와 맞춰 볼 수 있는 횟수
 * @param window 세는 구간. 처음 센 때부터 이 시간이 지나면 횟수가 지워진다
 */
@ConfigurationProperties("jey.dongtanpass.holder-check")
record HolderCheckProperties(int max, Duration window) {

	private static final String PREFIX = "jey.dongtanpass.holder-check.";

	// 단위를 빼먹은 "1"은 1밀리초로 읽힌다. 그런 값으로 뜨면 제한이 사실상 꺼진다.
	private static final Duration MIN_WINDOW = Duration.ofMinutes(1);

	HolderCheckProperties {
		if (window == null || window.compareTo(MIN_WINDOW) < 0) {
			throw new IllegalArgumentException(PREFIX + "window는 1분 이상이어야 한다(단위를 붙였는지 확인): " + window);
		}
		if (max < 1) {
			throw new IllegalArgumentException(PREFIX + "max는 1 이상이어야 한다: " + max);
		}
		// 한도가 받지 않는 값(구간 30일 초과 등)이면 뜰 때 막는다. 요청마다 만들다 실패하면 서버는 뜨고 미리 확인만 500이 된다.
		try {
			RateLimit.of(DongtanpassRateLimit.HOLDER_CHECK, max, window);
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException(PREFIX + "* 값으로 한도를 만들 수 없다: " + ex.getMessage(), ex);
		}
	}

	RateLimit limit() {
		return RateLimit.of(DongtanpassRateLimit.HOLDER_CHECK, max, window);
	}

}
