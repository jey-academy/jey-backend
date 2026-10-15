package com.jey.dongtanpass.domain;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PartnerRepository extends JpaRepository<Partner, Long> {

	List<Partner> findAllByOrderByIdAsc();

	/** 끄고 켤 때 쓴다. 그 학원의 행을 잠가, 같은 학원을 끄고 켜는 요청이 차례로 돌게 한다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from Partner p where p.id = :partnerId")
	Optional<Partner> findByIdForUpdate(@Param("partnerId") Long partnerId);

}
