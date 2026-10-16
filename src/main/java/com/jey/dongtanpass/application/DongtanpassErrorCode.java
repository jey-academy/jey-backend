package com.jey.dongtanpass.application;

import com.jey.core.shared.ErrorCode;
import org.springframework.http.HttpStatus;

public enum DongtanpassErrorCode implements ErrorCode {

	PARTNER_NOT_FOUND(HttpStatus.NOT_FOUND, "연계 학원을 찾을 수 없습니다."),

	// 관리자가 꺼진 학원에 계정을 만들려 했다. 꺼진 학원의 계정이 패스 기능을 쓰려 한 것(403)과 구분한다.
	PARTNER_INACTIVE_FOR_ACCOUNT(HttpStatus.CONFLICT, "꺼진 연계 학원에는 계정을 만들 수 없습니다. 학원을 먼저 켜 주세요."),

	// 로그인한 연계 학원 계정의 학원이 꺼졌다(또는 없다). 패스 모듈의 API를 요청마다 막는다(ADR-0034).
	// 학원을 다시 켜면 같은 요청이 지나가므로 잠시 막은 거부다(멱등 응답으로 보관하지 않는다).
	PARTNER_INACTIVE(HttpStatus.FORBIDDEN, "이용이 중지된 연계 학원입니다.", true),

	// DB의 비교 규칙이 대소문자를 가리지 않아, 대소문자만 다른 이름도 같은 이름이다.
	PARTNER_NAME_DUPLICATED(HttpStatus.CONFLICT, "같은 이름의 연계 학원이 이미 있습니다."),

	// 발급 요청의 동의 칸이 false다. 화면은 고지 문구를 보여 주고 확인을 받은 뒤에 보낸다.
	CONSENT_NOT_CONFIRMED(HttpStatus.BAD_REQUEST, "학부모에게 고지 문구를 보여 주고 동의를 받은 뒤에 발급할 수 있습니다."),

	// 화면이 예전 문구를 보여 주고 받은 동의다. 화면은 문구를 다시 받아 보여 준다.
	NOTICE_VERSION_MISMATCH(HttpStatus.BAD_REQUEST, "고지 문구가 바뀌었습니다. 새 문구를 보여 주고 다시 동의를 받아 주세요."),

	PAID_ON_IN_FUTURE(HttpStatus.BAD_REQUEST, "결제일은 오늘보다 뒤일 수 없습니다."),

	INVALID_PAID_AMOUNT(HttpStatus.BAD_REQUEST, "결제 금액은 0원보다 커야 하고 1원 단위여야 합니다."),

	// 없는 패스이거나 다른 연계 학원의 패스다. 있는지를 드러내지 않는다.
	PASS_NOT_FOUND(HttpStatus.NOT_FOUND, "패스를 찾을 수 없습니다."),

	PASS_CANCELED(HttpStatus.CONFLICT, "취소된 패스입니다."),

	// 쓰지 않은 채 만료일이 지났다. 학부모 QR을 주지 않는다.
	PASS_EXPIRED(HttpStatus.CONFLICT, "기간이 지난 패스입니다."),

	PASS_ALREADY_REDEEMED(HttpStatus.CONFLICT, "이미 사용된 패스입니다."),

	// 학부모 링크 다시 받기에서 이름과 번호가 여러 번 맞지 않았다(ADR-0035). 어느 기준에 걸렸는지 드러내지 않는다.
	HOLDER_RELINK_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "입력이 여러 번 맞지 않아 잠시 막았습니다. 잠시 뒤 다시 시도해 주세요."),

	// 장부의 기간이 틀렸다: 시작일이 종료일보다 뒤이거나, 날짜가 받는 범위(2000-01-01~2099-12-31) 밖이다.
	// 빈 장부로 답하지 않는다(화면의 실수가 "발급 없음"으로 보이지 않게).
	INVALID_PERIOD(HttpStatus.BAD_REQUEST, "기간이 올바르지 않습니다. 시작일은 종료일보다 뒤일 수 없고, 2000-01-01부터 2099-12-31 사이여야 합니다.");

	private final HttpStatus status;

	private final String message;

	private final boolean temporaryRejection;

	DongtanpassErrorCode(HttpStatus status, String message) {
		this(status, message, false);
	}

	// 상수마다 메서드를 재정의하지 않고 값으로 둔다. 재정의한 상수는 익명 하위 클래스가 되어 enum을 훑는 규칙 테스트가 상수를 읽지 못한다.
	DongtanpassErrorCode(HttpStatus status, String message, boolean temporaryRejection) {
		this.status = status;
		this.message = message;
		this.temporaryRejection = temporaryRejection;
	}

	@Override
	public String code() {
		return "DONGTANPASS_" + name();
	}

	@Override
	public HttpStatus status() {
		return status;
	}

	@Override
	public String message() {
		return message;
	}

	@Override
	public boolean temporaryRejection() {
		return temporaryRejection;
	}

}
