package com.jey.billing.domain;

import java.time.YearMonth;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

	/** 결제·결제 취소·청구 취소는 청구 행을 먼저 잠그고 상태를 본다(동시 요청이 차례로 돈다). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select i from Invoice i where i.id = :id")
	Optional<Invoice> findByIdForUpdate(@Param("id") long id);

	/** 전 지점의 그 달 청구. */
	Page<Invoice> findByTargetMonth(YearMonth targetMonth, Pageable pageable);

	/** 한 지점의 그 달 청구. */
	Page<Invoice> findByCampusIdAndTargetMonth(long campusId, YearMonth targetMonth, Pageable pageable);

}
