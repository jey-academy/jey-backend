package com.jey.billing.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface LedgerSyncRepository extends JpaRepository<LedgerSync, Long> {

	Optional<LedgerSync> findByPaymentId(long paymentId);

	/** 잠가서 읽는다. 맡기({@link LedgerSync#claim})와 결과 적기는 이것으로 읽은 뒤에 한다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from LedgerSync s where s.id = :id")
	Optional<LedgerSync> findByIdForUpdate(@Param("id") long id);

	/** 다시 시도할 줄: 다음 시도 시각이 된 대기 줄과, {@code staleBefore} 전에 맡은 채 시도 중인 줄. 오래된 것부터. */
	@Query("""
			select s.id from LedgerSync s
			where (s.status = com.jey.billing.domain.LedgerSyncStatus.PENDING and s.nextAttemptAt <= :now)
			   or (s.status = com.jey.billing.domain.LedgerSyncStatus.IN_PROGRESS and s.lastAttemptAt < :staleBefore)
			order by s.id""")
	List<Long> findDueIds(@Param("now") Instant now, @Param("staleBefore") Instant staleBefore, Pageable pageable);

}
