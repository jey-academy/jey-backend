package com.jey.billing.domain;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

	/**
	 * 결제의 청구 ID만 읽는다. 결제 취소는 청구를 잠근 뒤에 결제를 읽는다(먼저 읽어 둔 결제는 잠금을 기다리는 사이 낡을 수 있다).
	 */
	@Query("select p.invoiceId from Payment p where p.id = :id")
	Optional<Long> findInvoiceIdById(@Param("id") long id);

	/** 청구의 유효한 결제(COMPLETED는 하나뿐이다). */
	Optional<Payment> findByInvoiceIdAndStatus(long invoiceId, PaymentStatus status);

	/** 전 지점의 결제. 결제 시각 [from, before). */
	Page<Payment> findByPaidAtGreaterThanEqualAndPaidAtLessThan(Instant from, Instant before, Pageable pageable);

	/** 한 지점의 결제. 결제 시각 [from, before). */
	Page<Payment> findByCampusIdAndPaidAtGreaterThanEqualAndPaidAtLessThan(long campusId, Instant from,
			Instant before, Pageable pageable);

}
