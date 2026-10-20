package com.jey.billing.domain;

import java.time.YearMonth;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

	/** 전 지점의 그 달 청구. */
	Page<Invoice> findByTargetMonth(YearMonth targetMonth, Pageable pageable);

	/** 한 지점의 그 달 청구. */
	Page<Invoice> findByCampusIdAndTargetMonth(long campusId, YearMonth targetMonth, Pageable pageable);

}
