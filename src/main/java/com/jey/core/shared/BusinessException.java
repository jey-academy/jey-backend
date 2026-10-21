package com.jey.core.shared;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 에러 코드를 가진 예외. 각 모듈은 자기 {@link ErrorCode}를 담아 던진다.
 * 응답 변환기는 모듈별 예외 타입을 몰라도 이 타입 하나로 상태, code, detail을 만든다.
 *
 * <p>화면이 다음 행동에 쓸 ID를 오류 본문에 덧붙일 수 있다({@link #withId}, 예: 이미 있는 청구로 바로 가게).
 * 덧붙일 수 있는 값은 ID(숫자)뿐이다 — 이름·전화번호 같은 개인정보를 실을 자리를 만들지 않는다.
 */
public class BusinessException extends RuntimeException {

	// 소문자로 시작하는 camelCase이고 Id로 끝난다(invoiceId). 오류 본문의 다른 칸과 겹치지 않는다.
	private static final Pattern ID_NAME = Pattern.compile("[a-z][a-zA-Z0-9]*[a-z0-9]Id");

	// 오류 본문이 이미 쓰는 칸. 덧붙일 ID가 덮어쓰면 안 된다.
	private static final Set<String> RESERVED = Set.of("requestId");

	private final ErrorCode errorCode;

	private final Map<String, Long> ids = new LinkedHashMap<>();

	public BusinessException(ErrorCode errorCode) {
		this(errorCode, null, null);
	}

	/**
	 * @param detail 사용자에게 그대로 보여주는 문구. 개인정보(전화번호 등)나 내부 식별자를 넣지 않는다.
	 * 비어 있으면 에러 코드의 기본 문구를 쓴다.
	 */
	public BusinessException(ErrorCode errorCode, String detail) {
		this(errorCode, detail, null);
	}

	/**
	 * @param detail 사용자에게 그대로 보여주는 문구. 개인정보(전화번호 등)나 내부 식별자를 넣지 않는다.
	 * 비어 있으면 에러 코드의 기본 문구를 쓴다.
	 * @param cause 원인 예외. 응답에는 나가지 않고 로그에만 남는다.
	 */
	public BusinessException(ErrorCode errorCode, String detail, Throwable cause) {
		super(detailOrDefault(Objects.requireNonNull(errorCode, "errorCode"), detail), cause);
		this.errorCode = errorCode;
	}

	public ErrorCode getErrorCode() {
		return errorCode;
	}

	/**
	 * 오류 본문(RFC 9457의 확장 칸)에 ID를 덧붙인다. 그 ID의 자료를 볼 수 있는 사람에게만 나가는 오류에 쓴다(권한 확인 뒤).
	 *
	 * @param name {@code invoiceId}처럼 소문자로 시작하고 {@code Id}로 끝나는 이름. {@code requestId}는 쓸 수 없다
	 * @throws IllegalArgumentException 이름이 규칙 밖이거나 같은 이름을 두 번 담을 때(코드의 버그)
	 */
	public BusinessException withId(String name, long value) {
		if (name == null || !ID_NAME.matcher(name).matches() || RESERVED.contains(name)) {
			throw new IllegalArgumentException("오류 본문의 ID 이름은 소문자로 시작하고 Id로 끝나야 한다(requestId 제외): " + name);
		}
		if (ids.putIfAbsent(name, value) != null) {
			throw new IllegalArgumentException("같은 ID를 두 번 담았다: " + name);
		}
		return this;
	}

	/** 덧붙인 ID(담은 순서). 없으면 비어 있다. */
	public Map<String, Long> ids() {
		return Collections.unmodifiableMap(ids);
	}

	private static String detailOrDefault(ErrorCode errorCode, String detail) {
		return (detail == null || detail.isBlank()) ? errorCode.message() : detail;
	}

}
