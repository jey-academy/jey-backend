package com.jey.core.shared;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 모든 도메인 이벤트가 공통으로 갖는 봉투 정보.
 *
 * @param eventId 이벤트 하나를 가리키는 ID. 같은 이벤트가 두 번 도착했는지 가릴 때 쓴다.
 * @param eventType {@code {모듈}.{사건}} 형식의 이름(예: {@code billing.payment-completed}). 한 번 내보낸 이름은 바꾸지 않는다.
 * @param version 이벤트 내용의 버전. 필드 추가는 같은 버전에서 하고, 의미가 바뀌면 올린다.
 * @param occurredAt 사건이 일어난 시각
 * @param campusId 사건이 속한 지점. 지점과 무관하면 {@code null}
 */
public record EventMetadata(UUID eventId, String eventType, int version, Instant occurredAt, Long campusId) {

	private static final Pattern EVENT_TYPE = Pattern.compile("[a-z][a-z0-9]*\\.[a-z][a-z0-9]*(-[a-z0-9]+)*");

	public EventMetadata {
		Objects.requireNonNull(eventId, "eventId");
		Objects.requireNonNull(eventType, "eventType");
		Objects.requireNonNull(occurredAt, "occurredAt");
		if (!EVENT_TYPE.matcher(eventType).matches()) {
			throw new IllegalArgumentException("eventType은 {모듈}.{사건} 형식의 소문자 kebab-case여야 한다: " + eventType);
		}
		if (version < 1) {
			throw new IllegalArgumentException("version은 1 이상이어야 한다: " + version);
		}
		if (campusId != null && campusId <= 0) {
			throw new IllegalArgumentException("campusId는 양수여야 한다: " + campusId);
		}
	}

	/** 새 이벤트의 봉투 정보를 만든다. ID와 발생 시각은 시계에서 얻는다. */
	public static EventMetadata create(String eventType, int version, Long campusId, Clock clock) {
		return new EventMetadata(UuidV7.generate(clock), eventType, version, clock.instant(), campusId);
	}

}
