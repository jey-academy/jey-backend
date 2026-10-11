package com.jey.core.auth.api;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 한 계정이 어떤 일을 얼마 동안 몇 번까지 할 수 있는지. 무엇을 제한할지는 쓰는 모듈이 정한다.
 *
 * @param name 무엇의 한도인지. {@code {모듈}.{대상}} 꼴의 소문자다(예: {@code member.phone-reveal}).
 * 횟수를 세는 키에 들어가므로 바꾸면 그때까지 센 횟수를 찾지 못해 처음부터 다시 센다
 * @param max 구간 안에서 할 수 있는 횟수
 * @param window 세는 구간. 처음 센 때부터 이 시간이 지나면 횟수가 지워진다
 */
public record RateLimit(String name, int max, Duration window) {

	// 키의 구분자(:)와 섞이지 않게 소문자, 숫자, 하이픈, 점만 받는다.
	private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)+");

	public RateLimit {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(window, "window");
		if (!NAME.matcher(name).matches()) {
			throw new IllegalArgumentException("한도의 이름은 {모듈}.{대상} 꼴의 소문자여야 한다: " + name);
		}
		if (max < 1) {
			throw new IllegalArgumentException("한도는 1 이상이어야 한다: " + max);
		}
		if (window.isZero() || window.isNegative()) {
			throw new IllegalArgumentException("구간은 0보다 길어야 한다: " + window);
		}
	}

}
