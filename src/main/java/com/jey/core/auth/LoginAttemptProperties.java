package com.jey.core.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 로그인 시도 제한 설정({@code jey.auth.login-attempt-limit.*}). 값이 잘못되면 서버가 뜨지 않는다.
 *
 * @param window 실패를 세는 기간. 첫 실패부터 이 시간이 지나면 횟수가 지워진다.
 * @param maxFailuresPerLoginIdAndIp 한 IP에서 한 아이디로 실패할 수 있는 횟수. 넘으면 그 IP에서 그 아이디로는 로그인할 수 없다.
 * @param maxFailuresPerIp 한 IP에서 아이디와 무관하게 실패할 수 있는 횟수. 넘으면 그 IP에서는 로그인할 수 없다.
 */
@ConfigurationProperties("jey.auth.login-attempt-limit")
record LoginAttemptProperties(@DefaultValue("15m") Duration window,
		@DefaultValue("5") int maxFailuresPerLoginIdAndIp, @DefaultValue("30") int maxFailuresPerIp) {

	private static final String PREFIX = "jey.auth.login-attempt-limit.";

	// 단위를 빼먹은 "15"는 15밀리초로 읽힌다. 그런 값으로 뜨면 제한이 사실상 꺼진다.
	private static final Duration MIN_WINDOW = Duration.ofMinutes(1);

	LoginAttemptProperties {
		if (window == null || window.compareTo(MIN_WINDOW) < 0) {
			throw new IllegalArgumentException(PREFIX + "window는 1분 이상이어야 한다(단위를 붙였는지 확인): " + window);
		}
		if (maxFailuresPerLoginIdAndIp < 1) {
			throw new IllegalArgumentException(
					PREFIX + "max-failures-per-login-id-and-ip는 1 이상이어야 한다: " + maxFailuresPerLoginIdAndIp);
		}
		// 한 아이디의 한도가 IP 전체의 한도보다 크면 IP 한도가 먼저 걸린다. 둘을 바꿔 적은 실수일 가능성이 크다.
		if (maxFailuresPerIp < maxFailuresPerLoginIdAndIp) {
			throw new IllegalArgumentException(PREFIX + "max-failures-per-ip(" + maxFailuresPerIp
					+ ")는 max-failures-per-login-id-and-ip(" + maxFailuresPerLoginIdAndIp + ")보다 작을 수 없다");
		}
	}

}
