package com.jey.core.campus.domain;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CampusRepository extends JpaRepository<Campus, Long> {

	List<Campus> findAllByOrderByIdAsc();

}
