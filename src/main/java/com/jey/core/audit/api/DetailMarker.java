package com.jey.core.audit.api;

import java.util.Arrays;
import java.util.Map;

/**
 * 기록의 상세를 싣지 못했을 때 상세 자리에 대신 들어가는 표시. {@code {"truncated": true}}처럼 키 하나짜리 객체다.
 *
 * <p>이 키들은 표시 전용이다. 직접 적는 기록은 같은 이름의 값을 실을 수 없다({@link AuditEntry}가 거부한다).
 * 이벤트가 같은 이름의 필드를 가지면 구분되지 않으므로 이벤트의 필드 이름으로도 쓰지 않는다.
 */
public enum DetailMarker {

	/** 이벤트의 내용이 상세 컬럼보다 커서 싣지 않았다. */
	TRUNCATED("truncated"),

	/** 이벤트의 내용에 전화번호처럼 보이는 값이 있어서 싣지 않았다. */
	REDACTED("redacted"),

	/** 이벤트의 내용을 JSON으로 바꾸지 못해서 싣지 않았다. 적을 때의 일이다. */
	UNREADABLE("unreadable"),

	/** 저장돼 있는 상세가 깨져 있어서 읽지 못했다. 읽을 때의 일이고, DB를 직접 고친 흔적일 수 있다. */
	CORRUPTED("corrupted");

	private final String key;

	DetailMarker(String key) {
		this.key = key;
	}

	public String key() {
		return key;
	}

	/** 저장하는 모양. */
	public String json() {
		return "{\"" + key + "\":true}";
	}

	/** 응답으로 내보내는 모양. */
	public Map<String, Object> asMap() {
		return Map.of(key, true);
	}

	static boolean isReserved(String key) {
		return Arrays.stream(values()).anyMatch(marker -> marker.key.equals(key));
	}

}
