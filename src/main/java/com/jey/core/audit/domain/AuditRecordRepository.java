package com.jey.core.audit.domain;

import java.time.Instant;
import java.util.Collection;

import com.jey.core.audit.api.AuditOutcome;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 기록은 추가만 한다. 이 저장소의 삭제 메서드는 쓰지 않는다.
public interface AuditRecordRepository extends JpaRepository<AuditRecord, Long> {

	/**
	 * 도메인 이벤트를 한 줄로 넣는다. 같은 이벤트 ID의 줄이 이미 있으면 아무것도 하지 않는다.
	 *
	 * <p>"있는지 확인하고 넣기"를 문장 하나로 한다. 따로 확인하면 그 사이에 같은 이벤트가 먼저 들어올 수 있고,
	 * 유니크 키 위반을 예외로 받으면 그 트랜잭션을 더 쓸 수 없어 연결을 하나 더 열어야 한다.
	 * {@code INSERT IGNORE}는 쓰지 않는다. 길이 초과 같은 다른 오류까지 삼킨다.
	 *
	 * @return 넣었으면 1, 이미 있었으면 0
	 */
	@Modifying
	@Query(value = """
			INSERT INTO core_audit_log (occurred_at, actor_id, action, outcome, target_type, target_id, campus_id,
			                            detail_json, event_id, created_at, updated_at, created_by)
			VALUES (:occurredAt, :actorId, :action, 'DONE', :targetType, :targetId, :campusId,
			        :detailJson, :eventId, :recordedAt, :recordedAt, NULL)
			ON DUPLICATE KEY UPDATE id = id
			""", nativeQuery = true)
	int insertEventIfAbsent(@Param("eventId") String eventId, @Param("occurredAt") Instant occurredAt,
			@Param("actorId") Long actorId, @Param("action") String action, @Param("targetType") String targetType,
			@Param("targetId") Long targetId, @Param("campusId") Long campusId,
			@Param("detailJson") String detailJson, @Param("recordedAt") Instant recordedAt);

	/**
	 * 행위자, 행위, 대상은 선택이다. 기간은 {@code from} 이상 {@code to} 미만이고 둘 다 넘겨야 한다.
	 *
	 * @param outcomes 볼 결과. 비어 있으면 안 된다
	 */
	@Query("""
			select a from AuditRecord a
			where (:actorId is null or a.actorId = :actorId)
			  and (:action is null or a.action = :action)
			  and a.outcome in :outcomes
			  and (:targetType is null or a.targetType = :targetType)
			  and (:targetId is null or a.targetId = :targetId)
			  and a.occurredAt >= :from and a.occurredAt < :to
			""")
	Page<AuditRecord> search(@Param("actorId") Long actorId, @Param("action") String action,
			@Param("outcomes") Collection<AuditOutcome> outcomes, @Param("targetType") String targetType,
			@Param("targetId") Long targetId, @Param("from") Instant from, @Param("to") Instant to,
			Pageable pageable);

}
