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

	// 사용 처리가 같은 패스를 고친 다른 요청(링크 새로 만들기 등)에 졌고, 패스는 아직 쓸 수 있다. 다시 보내면 지나간다.
	// 잠시 막은 거부다(멱등 응답으로 보관하지 않는다 — 같은 키로 다시 보내도 처리된다).
	PASS_CHANGED_CONCURRENTLY(HttpStatus.CONFLICT, "패스가 방금 바뀌었습니다. 다시 시도해 주세요.", true),

	// 패스 할인 유형이 꺼져 있다. 사용 처리만 막는다(확인과 미리 확인은 그대로).
	// 관리자가 다시 켜면 같은 요청이 지나가므로 잠시 막은 거부다(멱등 응답으로 보관하지 않는다).
	DISCOUNT_INACTIVE(HttpStatus.CONFLICT, "패스 할인이 꺼져 있어 사용 처리할 수 없습니다. 관리자에게 문의해 주세요.", true),

	// 사용 처리에 넘긴 학생 ID가 없다.
	STUDENT_NOT_FOUND(HttpStatus.NOT_FOUND, "학생을 찾을 수 없습니다."),

	// 학생의 지점이 패스를 받는 지점이 아니다(관리자가 다른 지점 학생에게 쓰려 했거나, 등록 폼에서 지점을 잘못 골랐다).
	STUDENT_NOT_AT_PROVIDING_CAMPUS(HttpStatus.CONFLICT, "패스를 받는 지점의 학생이 아닙니다. 학생의 지점을 확인해 주세요."),

	// 신규 학생이 아니다: 이미 패스를 썼거나, 학생 자료를 만든 지 정해진 기간(7일)이 지났거나, 재원 중이 아니다(ADR-0033).
	STUDENT_NOT_NEW(HttpStatus.CONFLICT, "새로 등록한 학생에게만 패스를 쓸 수 있습니다. 이미 패스를 썼거나, 등록한 지 오래됐거나, 재원 중이 아닌 학생입니다."),

	// 학생 이름이 패스의 이름과 다르다(띄어쓰기는 무관). 이름과 번호가 모두 다르면 이것이 나간다.
	HOLDER_NAME_MISMATCH(HttpStatus.CONFLICT, "학생 이름이 패스의 이름과 다릅니다. 연계 학원에 확인해 주세요."),

	// 학생의 학부모 중 패스를 받을 때 적은 번호의 학부모가 없다.
	HOLDER_PHONE_MISMATCH(HttpStatus.CONFLICT, "학생의 학부모 번호 중에 패스를 받을 때 적은 번호가 없습니다. 학부모 번호를 더하거나 연계 학원에 확인해 주세요."),

	// 학부모 링크 다시 받기에서 이름과 번호가 여러 번 맞지 않았다(ADR-0035). 어느 기준에 걸렸는지 드러내지 않는다.
	HOLDER_RELINK_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "입력이 여러 번 맞지 않아 잠시 막았습니다. 잠시 뒤 다시 시도해 주세요."),

	// 데스크가 읽은 QR 토큰이 틀렸거나 만료됐거나 이미 쓴 것이다(ADR-0010). 어느 쪽인지 드러내지 않는다(이유는 서버 로그에만).
	QR_INVALID(HttpStatus.BAD_REQUEST, "QR을 확인할 수 없습니다. 학부모 화면에서 QR을 새로 받아 다시 읽어 주세요."),

	// 패스를 받지 않는 지점의 직원이 패스를 확인하거나 사용 처리하려 했다.
	CAMPUS_NOT_PROVIDING(HttpStatus.FORBIDDEN, "이 지점에서는 패스를 받지 않습니다."),

	// 데스크 계정이 패스 번호로 확인한 횟수가 한도를 넘었다(맞든 틀리든 센다). QR 확인은 세지 않는다.
	PASS_NO_VERIFY_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "패스 번호 확인이 너무 많습니다. 잠시 뒤 다시 시도하거나 QR로 확인해 주세요."),

	// 데스크 계정이 이름·학부모 번호를 패스와 맞춰 본 횟수가 한도를 넘었다(맞든 틀리든 센다).
	HOLDER_CHECK_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "본인 확인을 너무 많이 했습니다. 잠시 뒤 다시 시도해 주세요."),

	// 데스크 계정이 사용 처리를 부른 횟수가 한도를 넘었다(맞든 틀리든 센다).
	REDEEM_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "사용 처리를 너무 많이 했습니다. 잠시 뒤 다시 시도해 주세요."),

	// 장부의 기간이 틀렸다: 시작일이 종료일보다 뒤이거나, 날짜가 받는 범위(2000-01-01~2099-12-31) 밖이다.
	// 빈 장부로 답하지 않는다(화면의 실수가 "발급 없음"으로 보이지 않게).
	// 사용 내역의 기간이 1년(시작일 + 1년)을 넘었다. 아주 넓은 조회를 막는다.
	PERIOD_TOO_LONG(HttpStatus.BAD_REQUEST, "기간은 1년까지 볼 수 있습니다. 기간을 줄여 주세요."),

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
