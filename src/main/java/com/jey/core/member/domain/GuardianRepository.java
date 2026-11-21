package com.jey.core.member.domain;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface GuardianRepository extends JpaRepository<Guardian, Long> {

	/**
	 * @param fullIndex {@code PhoneProtector#fullIndex}로 만든 값
	 */
	@Query("select g from Guardian g where g.phone.fullIndex = :fullIndex")
	Optional<Guardian> findByPhoneIndex(@Param("fullIndex") String fullIndex);

	@Query("select count(g) > 0 from Guardian g where g.phone.fullIndex = :fullIndex and g.id <> :id")
	boolean existsOtherByPhoneIndex(@Param("fullIndex") String fullIndex, @Param("id") Long id);

	/**
	 * 학부모 행을 잠가서 읽는다. 학부모를 고치는 요청은 이것으로 읽어 줄을 세운다. 잠그지 않고 읽으면 다른 수정이 커밋하기 전의
	 * 값을 읽고, 저장할 때 모든 칸을 다시 써서 다른 수정이 바꾼 칸(번호)을 옛 값으로 되돌린다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select g from Guardian g where g.id = :id")
	Optional<Guardian> findByIdForUpdate(@Param("id") long id);

}
