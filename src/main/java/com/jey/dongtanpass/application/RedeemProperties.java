package com.jey.dongtanpass.application;

import java.time.Duration;
import java.time.Instant;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 패스 사용 처리의 설정({@code jey.dongtanpass.redeem.*}). 값이 없거나 잘못되면 서버가 뜨지 않는다.
 *
 * @param newStudentWindow 학생 자료를 만든 뒤 이 기간이 지나기 전까지 신규 학생이다(ADR-0033). 1일 이상 31일 이하
 */
@ConfigurationProperties("jey.dongtanpass.redeem")
record RedeemProperties(Duration newStudentWindow) {

	private static final String KEY = "jey.dongtanpass.redeem.new-student-window";

	// 단위를 빼먹은 "7"은 7밀리초로 읽혀 아무 학생도 신규가 아니게 된다. 너무 길면 다니던 학생도 신규가 된다.
	private static final Duration MIN = Duration.ofDays(1);

	private static final Duration MAX = Duration.ofDays(31);

	RedeemProperties {
		if (newStudentWindow == null || newStudentWindow.compareTo(MIN) < 0 || newStudentWindow.compareTo(MAX) > 0) {
			throw new IllegalArgumentException(KEY + "는 1일 이상 31일 이하여야 한다(단위를 붙였는지 확인): " + newStudentWindow);
		}
	}

	/**
	 * 만든 시각부터 기간이 지나기 전이면 신규다. 기간이 끝나는 그 시각부터는 아니다.
	 * 만든 시각이 지금보다 뒤여도(서버 시계의 작은 어긋남) 신규로 본다.
	 */
	boolean isNew(Instant createdAt, Instant now) {
		return now.isBefore(createdAt.plus(newStudentWindow));
	}

}
