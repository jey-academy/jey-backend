package com.jey.dongtanpass.domain;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RedemptionRepository extends JpaRepository<Redemption, Long> {

	/** 패스 하나에 기록은 하나다(유니크). 학부모 화면의 사용일에 쓴다. */
	Optional<Redemption> findByPassId(Long passId);

	/** 이 학생이 이미 패스를 썼는지. 학생 한 명에 기록은 하나다(유니크). */
	boolean existsByStudentId(Long studentId);

}
