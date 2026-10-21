package com.jey.dongtanpass.domain;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

/**
 * 사용 기록은 지우지 않는다. 지우면 유니크 키가 풀려 같은 학생·패스를 다시 쓸 수 있으므로 지우는 메서드를 열지 않는다.
 * 바뀌는 칸은 이 기록에서 만든 청구뿐이다({@link Redemption#attachInvoice}).
 */
public interface RedemptionRepository extends Repository<Redemption, Long> {

	/** 넣을 때 유니크 키에 걸리는지 바로 알도록 곧바로 DB에 보낸다. */
	Redemption saveAndFlush(Redemption redemption);

	Optional<Redemption> findById(Long id);

	/** 사용 기록에서 청구를 만들 때 먼저 잠근다(같은 기록으로 동시에 둘을 만들지 않게). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from Redemption r where r.id = :id")
	Optional<Redemption> findByIdForUpdate(@Param("id") long id);

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
			 pa.name, r.studentId, r.createdBy, r.invoiceId)
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

	/** 사용 기록 하나(목록 한 줄과 같은 모양). */
	@Query("""
			select new com.jey.dongtanpass.domain.RedemptionEntry(r.id, r.redeemedAt, r.campusId, p.id, p.passNo,
			 pa.name, r.studentId, r.createdBy, r.invoiceId)
			from Redemption r, Pass p, Partner pa
			where p.id = r.passId and pa.id = p.partnerId and r.id = :id
			""")
	Optional<RedemptionEntry> findEntry(@Param("id") long id);

}
