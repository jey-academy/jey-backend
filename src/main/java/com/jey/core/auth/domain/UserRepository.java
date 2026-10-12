package com.jey.core.auth.domain;

import java.util.List;
import java.util.Optional;

import com.jey.core.auth.api.UserRole;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

	Optional<User> findByLoginId(String loginId);

	/**
	 * 계정을 고치려고 읽을 때 쓴다. 그 계정의 행을 잠가, 같은 계정을 고치는 다른 요청이 이 트랜잭션이 끝날 때까지 기다리게 한다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from User u where u.id = :userId")
	Optional<User> findByIdForUpdate(@Param("userId") Long userId);

	/**
	 * 그 역할의 그 상태인 계정을 모두 잠그고 읽는다. "활성 관리자가 한 명은 남는다"를 확인할 때 쓴다.
	 * 잠그지 않고 세면, 두 관리자가 서로를 동시에 차단할 때 둘 다 "아직 한 명 더 있다"고 보고 통과한다.
	 *
	 * <p>관리자 수를 줄일 수 있는 변경은 이것을 트랜잭션의 첫 조회로 부른다. 잠그는 순서가 같아야 서로 물려 멈추지 않는다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from User u where u.role = :role and u.status = :status order by u.id")
	List<User> findAllByRoleAndStatusForUpdate(@Param("role") UserRole role, @Param("status") UserStatus status);

	/** 조건은 모두 선택이다. null이면 그 조건을 걸지 않는다. */
	@Query("""
			select u from User u
			where (:role is null or u.role = :role)
			  and (:campusId is null or u.campusId = :campusId)
			  and (:status is null or u.status = :status)
			""")
	Page<User> search(@Param("role") UserRole role, @Param("campusId") Long campusId,
			@Param("status") UserStatus status, Pageable pageable);

}
