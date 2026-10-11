package com.jey.core.member.domain;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StudentRepository extends JpaRepository<Student, Long> {

	String SEARCH_CONDITION = """
			where (:campusId is null or s.campusId = :campusId)
			  and (:namePrefix is null or s.name like :namePrefix escape '!')
			  and (:last4Index is null or s.phone.last4Index = :last4Index or exists (
			        select 1 from StudentGuardian sg, Guardian g
			        where sg.studentId = s.id and g.id = sg.guardianId and g.phone.last4Index = :last4Index))
			""";

	/**
	 * 조건은 모두 선택이다. 뒷자리는 학부모 번호와 학생 본인 번호를 모두 본다.
	 * 학부모 둘의 뒷자리가 같아도 학생은 한 번만 나온다(조인하지 않고 exists로 본다).
	 *
	 * @param namePrefix 이름의 앞부분에 {@code %}를 붙인 값. {@code %}, {@code _}, {@code !}는 {@code !}로 이스케이프해서 넘긴다
	 * @param last4Index {@code PhoneProtector#last4Index}로 만든 값
	 */
	@Query(value = "select s from Student s " + SEARCH_CONDITION,
			countQuery = "select count(s) from Student s " + SEARCH_CONDITION)
	Page<Student> search(@Param("campusId") Long campusId, @Param("namePrefix") String namePrefix,
			@Param("last4Index") String last4Index, Pageable pageable);

	/** 같은 학부모에 연결된 다른 학생. */
	@Query("""
			select s from Student s
			where s.id <> :studentId and exists (
			      select 1 from StudentGuardian mine, StudentGuardian theirs
			      where mine.studentId = :studentId and theirs.studentId = s.id
			        and mine.guardianId = theirs.guardianId)
			order by s.id
			""")
	List<Student> findSiblings(@Param("studentId") Long studentId);

	/** 이 학부모들 중 누구에게든 같은 이름의 학생이 이미 있는지. */
	@Query("""
			select count(s) > 0 from Student s
			where s.name = :name and exists (
			      select 1 from StudentGuardian sg
			      where sg.studentId = s.id and sg.guardianId in :guardianIds)
			""")
	boolean existsByNameAndAnyGuardian(@Param("name") String name,
			@Param("guardianIds") Collection<Long> guardianIds);

	/** 학부모 없이 본인 번호로만 등록한 학생의 중복 확인. */
	@Query("select count(s) > 0 from Student s where s.name = :name and s.phone.fullIndex = :fullIndex")
	boolean existsByNameAndPhoneIndex(@Param("name") String name, @Param("fullIndex") String fullIndex);

}
