package com.jey.dongtanpass.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PartnerRedemptionRepository extends JpaRepository<PartnerRedemption, Long> {

	/** 학부모 화면의 사용일. 패스 하나에 기록은 하나다. */
	Optional<PartnerRedemption> findByPassId(Long passId);

	/** 이 학원에서 이 학생이 제이G패스 할인을 이미 받았는지(신규 확인). 마지막 방어선은 유니크 키다. */
	boolean existsByPartnerIdAndStudentId(Long partnerId, Long studentId);

	/**
	 * 사용 내역. 패스 번호·학생 이름과 연계 학원 이름을 붙인다(같은 모듈의 테이블).
	 *
	 * @param partnerId 이 학원만. {@code null}이면 모든 학원
	 * @param from 이 시각부터(포함)
	 * @param before 이 시각 전까지(제외)
	 */
	@Query(value = """
			select new com.jey.dongtanpass.domain.PartnerRedemptionEntry(r.id, r.redeemedAt, r.partnerId, pa.name, p.id,
			 p.passNo, p.studentName, r.originalFee, r.discountRate, r.discountAmount, r.payableAmount)
			from PartnerRedemption r, Pass p, Partner pa
			where p.id = r.passId and pa.id = r.partnerId
			 and (:partnerId is null or r.partnerId = :partnerId)
			 and r.redeemedAt >= :from and r.redeemedAt < :before
			""", countQuery = """
			select count(r) from PartnerRedemption r
			where (:partnerId is null or r.partnerId = :partnerId)
			 and r.redeemedAt >= :from and r.redeemedAt < :before
			""")
	Page<PartnerRedemptionEntry> list(@Param("partnerId") Long partnerId, @Param("from") Instant from,
			@Param("before") Instant before, Pageable pageable);

	/**
	 * 장부 CSV: 그 학원이 할인해 준 기록. 순서는 정하지 않는다(부르는 쪽이 다른 기록과 섞어 정렬한다).
	 *
	 * @param from 이 시각부터(포함)
	 * @param before 이 시각 전까지(제외)
	 */
	@Query("""
			select new com.jey.dongtanpass.domain.PartnerDiscountLine(p.id, r.redeemedAt, p.studentName, p.passNo,
			 r.originalFee, r.discountRate, r.discountAmount, r.payableAmount, p.issuerCampusId)
			from PartnerRedemption r, Pass p
			where p.id = r.passId and r.partnerId = :partnerId
			 and r.redeemedAt >= :from and r.redeemedAt < :before
			""")
	List<PartnerDiscountLine> discountLines(@Param("partnerId") long partnerId, @Param("from") Instant from,
			@Param("before") Instant before);

}
