package com.jey.dongtanpass.domain;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PartnerRedemptionRepository extends JpaRepository<PartnerRedemption, Long> {

	/** 학부모 화면의 사용일. 패스 하나에 기록은 하나다. */
	Optional<PartnerRedemption> findByPassId(Long passId);

	/** 이 학원에서 이 학생이 JEY 패스 할인을 이미 받았는지(신규 확인). 마지막 방어선은 유니크 키다. */
	boolean existsByPartnerIdAndStudentId(Long partnerId, Long studentId);

}
