package com.jey.core.notification.typerules;

import com.jey.core.notification.api.NotificationType;

// 알림 종류의 규칙(NotificationTypeRulesTest)이 위반을 잡는지 보기 위한 본보기. 옳은 것 하나와 틀린 것 넷. 테스트 전용.
// 이 패키지는 com.jey.core.notification 아래라서 이름이 "notification."으로 시작해야 한다.
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

	// enum이 아니다. 어떤 종류가 있는지 모아 볼 수 없다.
	public static final class NotEnum implements NotificationType {

		@Override
		public String code() {
			return "notification.sample-made-at-runtime";
		}

	}

}
