package com.jey.dongtanpass.domain;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PartnerRepository extends JpaRepository<Partner, Long> {

	List<Partner> findAllByOrderByIdAsc();

}
