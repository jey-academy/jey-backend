package com.jey.core.notification.typerules;

import com.jey.core.notification.api.NotificationType;

// 알림 종류의 규칙(NotificationTypeRulesTest)이 위반을 잡는지 보기 위한 본보기. 옳은 것 둘과 틀린 것 여섯. 테스트 전용.
// 이 패키지는 com.jey.core.notification 아래라서 이름이 "notification."으로 시작해야 한다.
// 타입을 더하거나 빼면 NotificationTypeRulesTest의 "본보기를 모두 찾아낸다"의 목록도 고친다.
public final class SampleNotificationTypes {

	private SampleNotificationTypes() {
	}

	public enum Valid implements NotificationType {

		SAMPLE_SENT("notification.sample-sent"), SAMPLE_FAILED("notification.sample-failed");

		private final String code;

		Valid(String code) {
			this.code = code;
		}

		@Override
		public String code() {
			return code;
		}

	}

	// 옳다. 상수마다 몸통을 가진 enum은 상수의 클래스가 따로 생기는데(이 타입$1), 그것을 enum이 아닌 종류로 잘못 잡으면 안 된다.
	public enum WithConstantBody implements NotificationType {

		SAMPLE_OPENED {
			@Override
			public String code() {
				return "notification.sample-opened";
			}
		},
		SAMPLE_CLOSED {
			@Override
			public String code() {
				return "notification.sample-closed";
			}
		}

	}

	// 형식이 틀렸다(대문자와 밑줄).
	public enum BadFormat implements NotificationType {

		SAMPLE_SENT("notification.Sample_Sent");

		private final String code;

		BadFormat(String code) {
			this.code = code;
		}

		@Override
		public String code() {
			return code;
		}

	}

	// 다른 모듈의 이름으로 시작한다.
	public enum WrongModule implements NotificationType {

		SAMPLE_SENT("billing.sample-sent");

		private final String code;

		WrongModule(String code) {
			this.code = code;
		}

		@Override
		public String code() {
			return code;
		}

	}

	// 모듈 이름과 앞부분만 같다. 점까지 맞아야 한다.
	public enum PrefixWithoutDot implements NotificationType {

		SAMPLE_SENT("notificationx.sample-sent");

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
	public enum Duplicated implements NotificationType {

		SAMPLE_SENT_AGAIN("notification.sample-sent");

		private final String code;

		Duplicated(String code) {
			this.code = code;
		}

		@Override
		public String code() {
			return code;
		}

	}

	// 한 enum 안에서 두 상수가 같은 이름을 쓴다.
	public enum DuplicatedWithin implements NotificationType {

		SAMPLE_REPEATED("notification.sample-repeated"), SAMPLE_REPEATED_AGAIN("notification.sample-repeated");

		private final String code;

		DuplicatedWithin(String code) {
			this.code = code;
		}

		@Override
		public String code() {
			return code;
		}

	}

	// enum이 아니다. 어떤 종류가 있는지 모아 볼 수 없다.
	public static final class NotEnum implements NotificationType {

		@Override
		public String code() {
			return "notification.sample-made-at-runtime";
		}

	}

}
