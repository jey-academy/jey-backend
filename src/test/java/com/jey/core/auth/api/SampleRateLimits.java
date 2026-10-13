package com.jey.core.auth.api;

/**
 * {@code RateLimitRulesTest}의 규칙이 위반을 잡는지 보기 위한 본보기다. 운영 코드가 아니다.
 * 이 패키지는 {@code auth} 모듈에 있으므로 이름은 {@code auth.}로 시작해야 한다.
 */
public final class SampleRateLimits {

	private SampleRateLimits() {
	}

	public enum Valid implements RateLimitName {

		SAMPLE_THING("auth.sample-thing"), OTHER_THING("auth.other-thing");

		private final String value;

		Valid(String value) {
			this.value = value;
		}

		@Override
		public String value() {
			return value;
		}

	}

	// 대문자와 밑줄은 키에 쓰지 않는다.
	public enum BadFormat implements RateLimitName {

		SAMPLE("auth.Sample_Thing");

		private final String value;

		BadFormat(String value) {
			this.value = value;
		}

		@Override
		public String value() {
			return value;
		}

	}

	public enum WrongModule implements RateLimitName {

		SAMPLE("billing.sample-thing");

		private final String value;

		WrongModule(String value) {
			this.value = value;
		}

		@Override
		public String value() {
			return value;
		}

	}

	// 앞부분만 같은 다른 이름(authx)은 그 모듈의 이름이 아니다.
	public enum PrefixWithoutDot implements RateLimitName {

		SAMPLE("authx.sample-thing");

		private final String value;

		PrefixWithoutDot(String value) {
			this.value = value;
		}

		@Override
		public String value() {
			return value;
		}

	}

	// Valid가 이미 쓴 이름이다.
	public enum Duplicated implements RateLimitName {

		SAMPLE("auth.sample-thing");

		private final String value;

		Duplicated(String value) {
			this.value = value;
		}

		@Override
		public String value() {
			return value;
		}

	}

	public enum DuplicatedWithin implements RateLimitName {

		FIRST("auth.repeated-thing"), SECOND("auth.repeated-thing");

		private final String value;

		DuplicatedWithin(String value) {
			this.value = value;
		}

		@Override
		public String value() {
			return value;
		}

	}

	// enum이 아닌 이름은 선언된 것을 미리 모아 볼 수 없다.
	public static final class NotEnum implements RateLimitName {

		@Override
		public String value() {
			return "auth.not-enum";
		}

	}

}
