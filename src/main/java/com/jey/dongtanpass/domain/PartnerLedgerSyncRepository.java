package com.jey.dongtanpass.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface PartnerLedgerSyncRepository extends JpaRepository<PartnerLedgerSync, Long> {

	Optional<PartnerLedgerSync> findByPassId(long passId);

	/** 그 상태의 줄(관리자의 미반영 목록). */
	Page<PartnerLedgerSync> findByStatus(PartnerLedgerStatus status, Pageable pageable);

	/**
	 * 그 학원들을 뺀 그 상태의 줄(시트를 쓰지 않는 학원의 예전 미반영 줄을 뺀다). 부르는 쪽은 목록이 비었으면 {@link #findByStatus}를 쓴다
	 * (빈 목록도 지금은 모든 줄로 답하지만 그 동작에 기대지 않는다).
	 */
	Page<PartnerLedgerSync> findByStatusAndPartnerIdNotIn(PartnerLedgerStatus status, Collection<Long> partnerIds,
			Pageable pageable);

	/** 한 연계 학원의 그 상태의 줄. */
	Page<PartnerLedgerSync> findByStatusAndPartnerId(PartnerLedgerStatus status, long partnerId, Pageable pageable);

	/** 잠가서 읽는다. 맡기({@link PartnerLedgerSync#claim})와 결과 적기는 이것으로 읽은 뒤에 한다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from PartnerLedgerSync s where s.id = :id")
	Optional<PartnerLedgerSync> findByIdForUpdate(@Param("id") long id);

	/** 다시 시도할 줄: 다음 시도 시각이 된 대기 줄과, {@code staleBefore} 전에 맡은 채 시도 중인 줄. 오래된 것부터. */
	@Query("""
			select s.id from PartnerLedgerSync s
			where (s.status = com.jey.dongtanpass.domain.PartnerLedgerStatus.PENDING and s.nextAttemptAt <= :now)
			   or (s.status = com.jey.dongtanpass.domain.PartnerLedgerStatus.IN_PROGRESS and s.lastAttemptAt < :staleBefore)
			order by s.id""")
	List<Long> findDueIds(@Param("now") Instant now, @Param("staleBefore") Instant staleBefore, Pageable pageable);

}
