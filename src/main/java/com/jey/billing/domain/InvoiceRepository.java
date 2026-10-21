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

	/**
	 * 그 학생의 그 달 열린 청구(결제 전·결제함)의 ID와 지점. 열린 청구만 {@code open_key = 1}이고 유니크 키가 하나뿐임을 보장한다
	 * — 상태 목록이 아니라 그 칸으로 찾는다(닫힌 상태가 늘어도 두 줄이 걸리지 않게).
	 */
	@Query("select new com.jey.billing.domain.OpenInvoiceRef(i.id, i.campusId) from Invoice i "
			+ "where i.studentId = :studentId and i.targetMonth = :targetMonth and i.openKey = 1")
	Optional<OpenInvoiceRef> findOpen(@Param("studentId") long studentId, @Param("targetMonth") YearMonth targetMonth);

}
