package com.jey.core.audit.domain;

import java.time.Instant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 기록은 추가만 한다. 이 저장소의 삭제 메서드는 쓰지 않는다.
public interface AuditRecordRepository extends JpaRepository<AuditRecord, Long> {

	boolean existsByEventId(String eventId);

	/**
	 * 행위자, 행위, 대상은 선택이다. 기간은 {@code from} 이상 {@code to} 미만이고 둘 다 넘겨야 한다.
	 */
	@Query("""
			select a from AuditRecord a
			where (:actorId is null or a.actorId = :actorId)
			  and (:action is null or a.action = :action)
			  and (:targetType is null or a.targetType = :targetType)
			  and (:targetId is null or a.targetId = :targetId)
			  and a.occurredAt >= :from and a.occurredAt < :to
			""")
	Page<AuditRecord> search(@Param("actorId") Long actorId, @Param("action") String action,
			@Param("targetType") String targetType, @Param("targetId") Long targetId, @Param("from") Instant from,
			@Param("to") Instant to, Pageable pageable);

}
