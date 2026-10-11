package com.jey.core.campus.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DiscountTypeRepository extends JpaRepository<DiscountType, Long> {

	List<DiscountType> findAllByOrderByIdAsc();

	Optional<DiscountType> findByCode(String code);

	boolean existsByCode(String code);

}
