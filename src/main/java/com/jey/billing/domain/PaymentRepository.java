package com.jey.billing.domain;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

	/** 결제의 청구 ID만 읽는다. 결제 취소는 이것으로 청구를 먼저 잠그고, 그다음 결제를 잠가서 읽는다({@link #findByIdForUpdate}). */
	@Query("select p.invoiceId from Payment p where p.id = :id")
	Optional<Long> findInvoiceIdById(@Param("id") long id);

	/**
	 * 결제를 잠가서 읽는다. 잠그는 읽기는 최신 값을 본다. 잠그지 않는 읽기는 트랜잭션의 첫 읽기 때 정해진 스냅숏(MySQL REPEATABLE READ)을
	 * 보므로, 청구 잠금을 기다리는 사이 다른 요청이 취소한 결제를 아직 유효한 것으로 읽는다. 잠금 순서는 청구 → 결제다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from Payment p where p.id = :id")
	Optional<Payment> findByIdForUpdate(@Param("id") long id);

	/** 청구의 유효한 결제(COMPLETED는 하나뿐이다). */
	Optional<Payment> findByInvoiceIdAndStatus(long invoiceId, PaymentStatus status);

	/** 전 지점의 결제. 결제 시각 [from, before). */
	Page<Payment> findByPaidAtGreaterThanEqualAndPaidAtLessThan(Instant from, Instant before, Pageable pageable);

	/** 한 지점의 결제. 결제 시각 [from, before). */
	Page<Payment> findByCampusIdAndPaidAtGreaterThanEqualAndPaidAtLessThan(long campusId, Instant from,
			Instant before, Pageable pageable);

}
