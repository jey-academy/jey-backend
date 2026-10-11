package com.jey.core.audit.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.jey.core.shared.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 감사 로그의 한 줄. 만들고 나면 바뀌지 않는다. 변경 메서드를 두지 않는다.
 *
 * <p>{@code occurredAt}은 그 일이 일어난 시각이고 {@code createdAt}은 이 줄이 적힌 시각이다. 이벤트로 들어온 기록은 둘이 다르다.
 * 누가 했는지는 {@code actorId}를 본다. {@code createdBy}는 이 줄을 저장한 요청의 계정이라,
 * 요청 밖에서 저장되는 이벤트 기록에서는 비어 있다.
 */
@Entity
@Table(name = "core_audit_log")
public class AuditRecord extends BaseEntity {

	public static final int MAX_ACTION_LENGTH = 80;

	public static final int MAX_DETAIL_LENGTH = 2000;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "occurred_at", nullable = false, updatable = false)
	private Instant occurredAt;

	// 로그인 없이 일어난 일은 null이다.
	@Column(name = "actor_id", updatable = false)
	private Long actorId;

	@Column(nullable = false, updatable = false, length = MAX_ACTION_LENGTH)
	private String action;

	@Column(name = "target_type", updatable = false, length = 40)
	private String targetType;

	@Column(name = "target_id", updatable = false)
	private Long targetId;

	@Column(name = "campus_id", updatable = false)
	private Long campusId;

	// JSON 객체 하나. 없으면 null이다.
	@Column(name = "detail_json", updatable = false, length = MAX_DETAIL_LENGTH)
	private String detailJson;

	// 이벤트로 들어온 기록만 갖는다.
	@Column(name = "event_id", updatable = false, length = 36)
	private String eventId;

	protected AuditRecord() {
	}

	private AuditRecord(Instant occurredAt, Long actorId, String action, String targetType, Long targetId,
			Long campusId, String detailJson, String eventId) {
		this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
		this.actorId = actorId;
		this.action = Objects.requireNonNull(action, "action");
		this.targetType = targetType;
		this.targetId = targetId;
		this.campusId = campusId;
		this.detailJson = detailJson;
		this.eventId = eventId;
	}

	/** 직접 적은 기록. */
	public static AuditRecord direct(Instant occurredAt, Long actorId, String action, String targetType,
			Long targetId, String detailJson) {
		return new AuditRecord(occurredAt, actorId, action, targetType, targetId, null, detailJson, null);
	}

	/** 도메인 이벤트로 들어온 기록. 같은 이벤트가 다시 와도 {@code eventId}의 유니크 키가 한 줄만 남긴다. */
	public static AuditRecord ofEvent(UUID eventId, Instant occurredAt, Long actorId, String action, Long campusId,
			String detailJson) {
		return new AuditRecord(occurredAt, actorId, action, null, null, campusId, detailJson,
				Objects.requireNonNull(eventId, "eventId").toString());
	}

	public Long getId() {
		return id;
	}

	public Instant getOccurredAt() {
		return occurredAt;
	}

	public Long getActorId() {
		return actorId;
	}

	public String getAction() {
		return action;
	}

	public String getTargetType() {
		return targetType;
	}

	public Long getTargetId() {
		return targetId;
	}

	public Long getCampusId() {
		return campusId;
	}

	public String getDetailJson() {
		return detailJson;
	}

	public String getEventId() {
		return eventId;
	}

}
