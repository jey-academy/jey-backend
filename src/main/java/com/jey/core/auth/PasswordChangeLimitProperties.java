package com.jey.core.auth;

import java.time.Duration;

import com.jey.core.auth.api.RateLimit;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 본인 비밀번호 변경의 횟수 한도({@code jey.auth.password-change-limit.*}, ADR-0030). 값이 잘못되면 서버가 뜨지 않는다.
 *
 * <p>로그인된 세션을 손에 넣은 사람이 "지금 비밀번호"를 대입으로 알아내는 것을 느리게 한다. 틀린 것만 센다.
 *
 * @param window 세는 구간. 그 계정이 비밀번호 변경을 처음 시도한 때(맞은 것 포함)부터 이 시간이 지나면 횟수가 지워진다.
 * 그래서 막힌 뒤 풀릴 때까지의 시간은 이보다 짧을 수 있다
 * @param maxFailures 구간 안에서 지금 비밀번호를 틀릴 수 있는 횟수. 넘으면 맞는 비밀번호로도 바꿀 수 없다
 */
@ConfigurationProperties("jey.auth.password-change-limit")
record PasswordChangeLimitProperties(@DefaultValue("15m") Duration window, @DefaultValue("5") int maxFailures) {

	private static final String PREFIX = "jey.auth.password-change-limit.";

	// 단위를 빼먹은 "15"는 15밀리초로 읽힌다. 그런 값으로 뜨면 제한이 사실상 꺼진다.
	private static final Duration MIN_WINDOW = Duration.ofMinutes(1);

	PasswordChangeLimitProperties {
		if (window == null || window.compareTo(MIN_WINDOW) < 0) {
			throw new IllegalArgumentException(PREFIX + "window는 1분 이상이어야 한다(단위를 붙였는지 확인): " + window);
		}
		if (maxFailures < 1) {
			throw new IllegalArgumentException(PREFIX + "max-failures는 1 이상이어야 한다: " + maxFailures);
		}
	}

	RateLimit toRateLimit() {
		return RateLimit.of(AuthRateLimit.PASSWORD_CHANGE, maxFailures, window);
	}

}
