package com.jey.core.auth.api;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 한 계정이 어떤 일을 얼마 동안 몇 번까지 할 수 있는지. 무엇을 제한할지는 쓰는 모듈이 정한다.
 *
 * <p>{@link #of}로 만든다. 이름은 모듈마다 enum으로 선언한 {@link RateLimitName}이다. 생성자는 직접 부르지 않는다
 * ({@code RateLimitRulesTest}가 막는다). 이름을 글자로 적어 만들면 다른 한도와 겹치는지 미리 볼 수 없다.
 *
 * @param name 무엇의 한도인지. {@code {모듈}.{대상}} 꼴의 소문자다(예: {@code member.phone-reveal}).
 * 횟수를 세는 키에 들어가므로 바꾸면 그때까지 센 횟수를 찾지 못해 처음부터 다시 센다
 * @param max 구간 안에서 할 수 있는 횟수
 * @param window 세는 구간. 처음 센 때부터 이 시간이 지나면 횟수가 지워진다. 1밀리초 이상 30일 이하다
 */
public record RateLimit(String name, int max, Duration window) {

	// 키의 구분자(:)와 섞이지 않게 소문자, 숫자, 하이픈, 점만 받는다.
	private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)+");

	// 횟수는 밀리초 단위의 만료 시간으로 센다. 그보다 짧은 구간은 0밀리초가 되어 센 것이 바로 지워지고 한도가 꺼진다.
	private static final Duration MIN_WINDOW = Duration.ofMillis(1);

	// 고정 구간이라 이보다 길게 셀 일이 없다. 지나치게 긴 값은 밀리초로 바꿀 때 넘치거나 저장소가 받지 않는다.
	private static final Duration MAX_WINDOW = Duration.ofDays(30);

	/**
	 * @param name 모듈마다 enum으로 선언한 이름
	 * @throws NullPointerException 이름이나 구간이 없을 때
	 * @throws IllegalArgumentException 이름이 enum이 아니거나, 이름·횟수·구간이 틀렸을 때
	 */
	public static RateLimit of(RateLimitName name, int max, Duration window) {
		Objects.requireNonNull(name, "name");
		// 람다나 그때그때 만든 객체로 넘긴 이름은 규칙 테스트가 미리 볼 수 없다. 선언해 둔 enum만 받는다.
		if (!(name instanceof Enum<?> declared)) {
			throw new IllegalArgumentException("한도의 이름은 enum으로 선언한다: " + name.getClass().getName());
		}
		String code = name.code();
		if (code == null) {
			throw new IllegalArgumentException(
					"한도의 이름이 비었다: " + declared.getDeclaringClass().getName() + "." + declared.name());
		}
		return new RateLimit(code, max, window);
	}

	/** 한도의 이름으로 쓸 수 있는 형식인지. {@code {모듈}.{대상}} 꼴의 소문자다. 형식을 보는 곳은 여기 하나다. */
	public static boolean isValidName(String name) {
		return name != null && NAME.matcher(name).matches();
	}

	public RateLimit {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(window, "window");
		if (!isValidName(name)) {
			throw new IllegalArgumentException("한도의 이름은 {모듈}.{대상} 꼴의 소문자여야 한다: " + name);
		}
		if (max < 1) {
			throw new IllegalArgumentException("한도는 1 이상이어야 한다: " + max);
		}
		if (window.compareTo(MIN_WINDOW) < 0 || window.compareTo(MAX_WINDOW) > 0) {
			throw new IllegalArgumentException("구간은 1밀리초 이상 30일 이하여야 한다: " + window);
		}
	}

}
