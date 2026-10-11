package com.jey.core.audit.domain;

import java.time.Instant;
import java.util.Objects;

import com.jey.core.audit.api.AuditAction;
import com.jey.core.audit.api.AuditOutcome;
import com.jey.core.shared.BaseEntity;
import com.jey.core.shared.EntityRef;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 *
 * <p>직접 적은 기록은 이 엔티티로 저장한다. 이벤트로 들어온 기록은 {@link AuditRecordRepository#insertEventIfAbsent}가 넣고
 * {@code eventId}를 갖는다. 읽을 때는 둘 다 이 엔티티다.
 */
@Entity
@Table(name = "core_audit_log")
public class AuditRecord extends BaseEntity {

	public static final int MAX_ACTION_LENGTH = AuditAction.MAX_CODE_LENGTH;

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

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false, length = 10)
	private AuditOutcome outcome;

	// 대상의 종류와 ID는 둘 다 있거나 둘 다 없다.
	@Column(name = "target_type", updatable = false, length = EntityRef.MAX_TYPE_LENGTH)
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

	private AuditRecord(Instant occurredAt, Long actorId, String action, AuditOutcome outcome, EntityRef target,
			Long campusId, String detailJson) {
		this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
		this.actorId = actorId;
		this.action = Objects.requireNonNull(action, "action");
		this.outcome = Objects.requireNonNull(outcome, "outcome");
		this.targetType = (target != null) ? target.type() : null;
		this.targetId = (target != null) ? target.id() : null;
		this.campusId = campusId;
		this.detailJson = detailJson;
	}

	/**
	 * 직접 적은 기록.
	 *
	 * @param target 대상이 없으면 {@code null}
	 */
	public static AuditRecord direct(Instant occurredAt, Long actorId, String action, AuditOutcome outcome,
			EntityRef target, Long campusId, String detailJson) {
		return new AuditRecord(occurredAt, actorId, action, outcome, target, campusId, detailJson);
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

	public AuditOutcome getOutcome() {
		return outcome;
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
