package com.jey.core.member.domain;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GuardianRepository extends JpaRepository<Guardian, Long> {

	/**
	 * @param fullIndex {@code PhoneProtector#fullIndex}로 만든 값
	 */
	@Query("select g from Guardian g where g.phone.fullIndex = :fullIndex")
	Optional<Guardian> findByPhoneIndex(@Param("fullIndex") String fullIndex);

	@Query("select count(g) > 0 from Guardian g where g.phone.fullIndex = :fullIndex and g.id <> :id")
	boolean existsOtherByPhoneIndex(@Param("fullIndex") String fullIndex, @Param("id") Long id);

}
