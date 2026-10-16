package com.jey.dongtanpass.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PassRepository extends JpaRepository<Pass, Long> {

	/** 그 학원이 발급한 패스만 찾는다. 다른 학원의 패스는 없는 것과 같다. */
	Optional<Pass> findByIdAndPartnerId(Long id, Long partnerId);

	/** 학부모가 링크로 연다. 해시는 유니크다. */
	Optional<Pass> findByLinkTokenHash(String linkTokenHash);

	/** 학부모가 링크를 다시 받을 때 번호 색인으로 후보를 읽는다. 이름은 부르는 쪽이 맞춰 본다(저장된 이름은 입력한 표기 그대로다). */
	List<Pass> findByGuardianPhoneIndex(String guardianPhoneIndex);

	/** 학부모가 다시 받기로 고른 패스를 잠근다. 같은 패스를 고치는 요청(링크 새로 만들기, 취소, 사용 처리)과 차례로 돈다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from Pass p where p.id = :id")
	Optional<Pass> findByIdForUpdate(@Param("id") Long id);

	/** 링크 새로 만들기와 취소. 같은 패스를 고치는 요청이 차례로 돈다(사용 처리와 겹치면 버전이 막는다). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from Pass p where p.id = :id and p.partnerId = :partnerId")
	Optional<Pass> findByIdAndPartnerIdForUpdate(@Param("id") Long id, @Param("partnerId") Long partnerId);

	/**
	 * 발급 장부. 조건은 모두 선택이다(null이면 걸지 않는다).
	 *
	 * @param issuedFrom 이 시각부터(포함)
	 * @param issuedBefore 이 시각 전까지(제외)
	 * @param expiresOnOrAfter 만료일이 이날 이후(포함)
	 * @param expiresBefore 만료일이 이날 전(제외)
	 */
	@Query("""
			select p from Pass p where p.partnerId = :partnerId
			 and (:issuedFrom is null or p.issuedAt >= :issuedFrom)
			 and (:issuedBefore is null or p.issuedAt < :issuedBefore)
			 and (:status is null or p.status = :status)
			 and (:expiresOnOrAfter is null or p.expiresOn >= :expiresOnOrAfter)
			 and (:expiresBefore is null or p.expiresOn < :expiresBefore)
			""")
	Page<Pass> ledger(@Param("partnerId") Long partnerId, @Param("issuedFrom") Instant issuedFrom,
			@Param("issuedBefore") Instant issuedBefore, @Param("status") PassStatus status,
			@Param("expiresOnOrAfter") LocalDate expiresOnOrAfter, @Param("expiresBefore") LocalDate expiresBefore,
			Pageable pageable);

}
