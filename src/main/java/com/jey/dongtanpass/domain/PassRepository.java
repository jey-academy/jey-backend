package com.jey.dongtanpass.domain;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PassRepository extends JpaRepository<Pass, Long> {

	/** 그 학원이 발급한 패스만 찾는다. 다른 학원의 패스는 없는 것과 같다. */
	Optional<Pass> findByIdAndPartnerId(Long id, Long partnerId);

}
