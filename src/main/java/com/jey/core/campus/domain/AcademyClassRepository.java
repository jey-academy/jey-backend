package com.jey.core.campus.domain;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AcademyClassRepository extends JpaRepository<AcademyClass, Long> {

	List<AcademyClass> findAllByOrderByCampusIdAscNameAsc();

	List<AcademyClass> findByCampusIdOrderByNameAsc(Long campusId);

	List<AcademyClass> findByActiveTrueOrderByCampusIdAscNameAsc();

	List<AcademyClass> findByCampusIdAndActiveTrueOrderByNameAsc(Long campusId);

	boolean existsByCampusIdAndName(Long campusId, String name);

	boolean existsByCampusIdAndNameAndIdNot(Long campusId, String name, Long id);

}
