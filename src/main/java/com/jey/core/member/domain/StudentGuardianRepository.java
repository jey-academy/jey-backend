package com.jey.core.member.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StudentGuardianRepository extends JpaRepository<StudentGuardian, Long> {

	// ID 순서가 연결된 순서다. 대표 연락처 규칙이 이 순서를 쓴다.
	List<StudentGuardian> findByStudentIdOrderByIdAsc(Long studentId);

	List<StudentGuardian> findByStudentIdInOrderByIdAsc(Collection<Long> studentIds);

	Optional<StudentGuardian> findByStudentIdAndGuardianId(Long studentId, Long guardianId);

	boolean existsByStudentIdAndGuardianId(Long studentId, Long guardianId);

	long countByStudentId(Long studentId);

}
