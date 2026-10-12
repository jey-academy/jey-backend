package com.jey.core.notification;

import com.jey.core.notification.api.NotificationType;

// 모듈이 알림의 종류를 선언하는 모양의 본보기. 테스트 전용.
enum TestNotificationType implements NotificationType {

	TEST_HAPPENED("notification.test-happened");

	private final String code;

	TestNotificationType(String code) {
		this.code = code;
	}

	@Override
	public String code() {
		return code;
	}

}
