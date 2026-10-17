package com.jey.dongtanpass.domain;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RedemptionRepository extends JpaRepository<Redemption, Long> {

	/** 패스 하나에 기록은 하나다(유니크). 학부모 화면의 사용일에 쓴다. */
	Optional<Redemption> findByPassId(Long passId);

	/** 이 학생이 이미 패스를 썼는지. 학생 한 명에 기록은 하나다(유니크). */
	boolean existsByStudentId(Long studentId);

	/**
	 * 사용 내역. 패스 번호와 연계 학원 이름을 붙인다(같은 모듈의 테이블).
	 *
	 * @param campusId 이 지점만. {@code null}이면 모든 지점
	 * @param from 이 시각부터(포함)
	 * @param before 이 시각 전까지(제외)
	 */
	@Query(value = """
			select new com.jey.dongtanpass.domain.RedemptionEntry(r.id, r.redeemedAt, r.campusId, p.id, p.passNo,
			 pa.name, r.studentId, r.createdBy)
			from Redemption r, Pass p, Partner pa
			where p.id = r.passId and pa.id = p.partnerId
			 and (:campusId is null or r.campusId = :campusId)
			 and r.redeemedAt >= :from and r.redeemedAt < :before
			""", countQuery = """
			select count(r) from Redemption r
			where (:campusId is null or r.campusId = :campusId)
			 and r.redeemedAt >= :from and r.redeemedAt < :before
			""")
	Page<RedemptionEntry> list(@Param("campusId") Long campusId, @Param("from") Instant from,
			@Param("before") Instant before, Pageable pageable);

}
