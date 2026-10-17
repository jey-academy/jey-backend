package com.jey.core.member.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StudentGuardianRepository extends JpaRepository<StudentGuardian, Long> {

	// ID 순서가 연결된 순서다. 대표 연락처 규칙이 이 순서를 쓴다.
	List<StudentGuardian> findByStudentIdOrderByIdAsc(Long studentId);

	List<StudentGuardian> findByStudentIdInOrderByIdAsc(Collection<Long> studentIds);

	Optional<StudentGuardian> findByStudentIdAndGuardianId(Long studentId, Long guardianId);

	boolean existsByStudentIdAndGuardianId(Long studentId, Long guardianId);

	long countByStudentId(Long studentId);

	@Query("""
			select count(sg) > 0 from StudentGuardian sg, Guardian g
			where sg.studentId = :studentId and g.id = sg.guardianId and g.phone.fullIndex = :fullIndex
			""")
	boolean existsGuardianWithPhoneIndex(@Param("studentId") Long studentId, @Param("fullIndex") String fullIndex);

}
