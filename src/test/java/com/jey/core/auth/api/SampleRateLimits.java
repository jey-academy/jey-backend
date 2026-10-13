package com.jey.core.auth.api;

import java.time.Duration;

/**
 * {@code RateLimitRulesTest}의 규칙이 위반을 잡는지 보기 위한 본보기다. 운영 코드가 아니다.
 * 이 패키지는 {@code auth} 모듈에 있으므로 이름은 {@code auth.}로 시작해야 한다.
 */
public final class SampleRateLimits {

	private SampleRateLimits() {
	}

	public enum Valid implements RateLimitName {

		SAMPLE_THING("auth.sample-thing"), OTHER_THING("auth.other-thing");

		private final String code;

		Valid(String code) {
			this.code = code;
		}

		@Override
		public String code() {
			return code;
		}

	}

	// 대문자와 밑줄은 키에 쓰지 않는다.
	public enum BadFormat implements RateLimitName {

		SAMPLE("auth.Sample_Thing");

		private final String code;

		BadFormat(String code) {
			this.code = code;
		}

		@Override
		public String code() {
			return code;
		}

	}

	public enum WrongModule implements RateLimitName {

		SAMPLE("billing.sample-thing");

		private final String code;

		WrongModule(String code) {
			this.code = code;
		}

		@Override
		public String code() {
			return code;
		}

	}

	// 앞부분만 같은 다른 이름(authx)은 그 모듈의 이름이 아니다.
	public enum PrefixWithoutDot implements RateLimitName {

		SAMPLE("authx.sample-thing");

		private final String code;

		PrefixWithoutDot(String code) {
			this.code = code;
		}

		@Override
		public String code() {
			return code;
		}

	}

	// Valid가 이미 쓴 이름이다.
	public enum Duplicated implements RateLimitName {

		SAMPLE("auth.sample-thing");

		private final String code;

		Duplicated(String code) {
			this.code = code;
		}

		@Override
		public String code() {
			return code;
		}

	}

	public enum DuplicatedWithin implements RateLimitName {

		FIRST("auth.repeated-thing"), SECOND("auth.repeated-thing");

		private final String code;

		DuplicatedWithin(String code) {
			this.code = code;
		}

		@Override
		public String code() {
			return code;
		}

	}

	// enum이 아닌 이름은 선언된 것을 미리 모아 볼 수 없다.
	public static final class NotEnum implements RateLimitName {

		@Override
		public String code() {
			return "auth.not-enum";
		}

	}

	// 상수마다 몸통을 가진 enum은 상수의 클래스가 따로 생긴다. 이름을 선언한 것은 그 상수를 가진 enum 하나다.
	public enum WithConstantBody implements RateLimitName {

		SAMPLE {
			@Override
			public String code() {
				return "auth.constant-body";
			}
		}

	}

	// 아래 셋은 "한도는 선언한 이름으로만 만든다"는 규칙의 본보기다.
	// 생성자를 직접 부른다. 이름을 글자로 적어 만든 한도는 선언된 이름에 없어 겹치는지 미리 볼 수 없다.
	public static final class CallsConstructor {

		RateLimit make() {
			return new RateLimit("auth.typed-by-hand", 5, Duration.ofMinutes(1));
		}

	}

	// 생성자를 부르지 않고 가리키기만 해도(::new) 같은 길이다.
	public static final class ReferencesConstructor {

		interface Maker {

			RateLimit make(String name, int max, Duration window);

		}

		RateLimit make() {
			Maker maker = RateLimit::new;
			return maker.make("auth.typed-by-hand", 5, Duration.ofMinutes(1));
		}

	}

	// 지키는 쪽.
	public static final class UsesFactory {

		RateLimit make() {
			return RateLimit.of(Valid.SAMPLE_THING, 5, Duration.ofMinutes(1));
		}

	}

}
