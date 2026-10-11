package com.jey.core.audit.api;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import com.jey.core.shared.EntityRef;

/**
 * 감사 로그에 적을 한 줄. 누가 했는지는 여기에 없다. 기록할 때 로그인 정보에서 읽는다.
 *
 * <pre>
 * auditLog.record(AuditEntry.of(GUARDIAN_UNLINKED).target("student", studentId).campus(campusId)
 *         .with("guardianId", guardianId));
 * </pre>
 *
 * <p>덧붙이는 값은 숫자, 참·거짓, enum, 짧은 문자열만 된다. ID와 표시만 싣고 이름·전화번호 같은 개인정보는 싣지 않는다.
 * ID는 숫자로 싣는다. 문자열에 전화번호처럼 보이는 값이 있으면 기록할 때 거부된다.
 */
public final class AuditEntry {

	public static final int MAX_STRING_VALUE_LENGTH = 200;

	private final AuditAction action;

	private EntityRef target;

	private Long campusId;

	// 키 순서를 고정해서 같은 내용이 같은 문자열로 저장되게 한다.
	private final Map<String, Object> detail = new TreeMap<>();

	private AuditEntry(AuditAction action) {
		this.action = Objects.requireNonNull(action, "action");
	}

	public static AuditEntry of(AuditAction action) {
		return new AuditEntry(action);
	}

	/** 무엇에 대한 일인지. 특정 대상이 없으면 부르지 않는다. */
	public AuditEntry target(String type, long id) {
		this.target = EntityRef.of(type, id);
		return this;
	}

	/** 그 일이 속한 지점. 지점과 무관한 일이면 부르지 않는다. */
	public AuditEntry campus(long campusId) {
		if (campusId <= 0) {
			throw new IllegalArgumentException("campusId는 양수여야 한다: " + campusId);
		}
		this.campusId = campusId;
		return this;
	}

	public AuditEntry with(String key, long value) {
		return put(key, value);
	}

	public AuditEntry with(String key, boolean value) {
		return put(key, value);
	}

	/** enum은 이름으로 싣는다. */
	public AuditEntry with(String key, Enum<?> value) {
		return put(key, Objects.requireNonNull(value, key).name());
	}

	/**
	 * @throws IllegalArgumentException 문자열이 너무 길 때
	 */
	public AuditEntry with(String key, String value) {
		if (Objects.requireNonNull(value, key).length() > MAX_STRING_VALUE_LENGTH) {
			throw new IllegalArgumentException("감사 로그에 싣는 문자열이 너무 길다: " + key);
		}
		return put(key, value);
	}

	public AuditAction action() {
		return action;
	}

	/** 대상이 없으면 {@code null}. */
	public EntityRef target() {
		return target;
	}

	/** 지점과 무관하면 {@code null}. */
	public Long campusId() {
		return campusId;
	}

	public Map<String, Object> detail() {
		return Collections.unmodifiableMap(detail);
	}

	private AuditEntry put(String key, Object value) {
		if (key == null || key.isBlank()) {
			throw new IllegalArgumentException("감사 로그에 싣는 값의 이름은 비울 수 없다");
		}
		detail.put(key, value);
		return this;
	}

}
