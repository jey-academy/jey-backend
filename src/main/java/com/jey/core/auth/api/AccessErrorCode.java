package com.jey.core.auth.api;

import com.jey.core.shared.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 로그인은 했지만 허용되지 않은 요청의 에러 코드. 로그인 자체의 오류와 달리 다른 모듈의 테스트가 참조하므로 공개 패키지에 둔다.
 */
public enum AccessErrorCode implements ErrorCode {

	// 이 역할은 쓸 수 없는 기능이다. CSRF 거부(COMMON_FORBIDDEN)와 구분된다.
	ROLE_NOT_ALLOWED(HttpStatus.FORBIDDEN, "이 기능을 사용할 권한이 없습니다."),

	// 소속 지점으로 묶인 자료(수납, 수강료표, 패스 사용 내역)를 다른 지점 직원이나 지점 없는 계정이 다루려 했다.
	CAMPUS_NOT_ALLOWED(HttpStatus.FORBIDDEN, "이 지점의 자료를 다룰 권한이 없습니다.");

	private final HttpStatus status;

	private final String message;

	AccessErrorCode(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}

	@Override
	public String code() {
		return "AUTH_" + name();
	}

	@Override
	public HttpStatus status() {
		return status;
	}

	@Override
	public String message() {
		return message;
	}

}
