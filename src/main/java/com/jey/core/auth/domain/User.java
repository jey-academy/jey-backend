package com.jey.core.auth.domain;

import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 로그인하는 계정(직원, 관리자, 연계 학원). 학생·학부모 정보와는 별개다.
 */
@Entity
@Table(name = "core_user")
public class User extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "login_id", nullable = false, updatable = false, length = 50)
	private String loginId;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Column(nullable = false, length = 50)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private UserRole role;

	// 전 지점을 보는 관리자와 연계 학원은 지점이 없다.
	@Column(name = "campus_id")
	private Long campusId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private UserStatus status;

	protected User() {
	}

	private User(String loginId, String passwordHash, String name, UserRole role, Long campusId) {
		this.loginId = loginId;
		this.passwordHash = passwordHash;
		this.name = name;
		this.role = role;
		this.campusId = campusId;
		this.status = UserStatus.ACTIVE;
	}

	/**
	 * @param passwordHash 해시된 비밀번호. 원문을 넘기지 않는다.
	 */
	public static User create(String loginId, String passwordHash, String name, UserRole role, Long campusId) {
		requireText(loginId, "loginId");
		requireText(passwordHash, "passwordHash");
		requireText(name, "name");
		if (role == null) {
			throw new IllegalArgumentException("role은 비울 수 없다");
		}
		return new User(loginId, passwordHash, name, role, campusId);
	}

	public void disable() {
		this.status = UserStatus.DISABLED;
	}

	public boolean isActive() {
		return status == UserStatus.ACTIVE;
	}

	public Long getId() {
		return id;
	}

	public String getLoginId() {
		return loginId;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public String getName() {
		return name;
	}

	public UserRole getRole() {
		return role;
	}

	public Long getCampusId() {
		return campusId;
	}

	private static void requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + "은(는) 비울 수 없다");
		}
	}

}
