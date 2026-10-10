package com.jey.core.auth.domain;

import java.util.Locale;
import java.util.regex.Pattern;

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

	private static final int MAX_LOGIN_ID_LENGTH = 50;

	private static final int MAX_NAME_LENGTH = 50;

	// 영문 소문자, 숫자, 점, 밑줄, 하이픈만. 3~50자, 첫 글자는 영문이나 숫자.
	private static final Pattern LOGIN_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{2,49}");

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "login_id", nullable = false, updatable = false, length = MAX_LOGIN_ID_LENGTH)
	private String loginId;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Column(nullable = false, length = MAX_NAME_LENGTH)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private UserRole role;

	// 직원만 가진다. 관리자(전 지점)와 연계 학원은 지점이 없다.
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
	 * @param loginId 앞뒤 공백을 떼고 소문자로 바꿔 저장한다({@link #normalizeLoginId})
	 * @param passwordHash 해시된 비밀번호. 원문을 넘기지 않는다.
	 * @throws IllegalArgumentException 필수값이 비었거나, 길이가 넘거나, 해시가 아니거나, 역할과 지점의 조합이 맞지 않을 때
	 */
	public static User create(String loginId, String passwordHash, String name, UserRole role, Long campusId) {
		requireText(loginId, "loginId");
		requireText(passwordHash, "passwordHash");
		requireText(name, "name");
		if (role == null) {
			throw new IllegalArgumentException("role은 비울 수 없다");
		}
		String normalizedLoginId = normalizeLoginId(loginId);
		if (!isValidLoginId(normalizedLoginId)) {
			throw new IllegalArgumentException("loginId는 영문 소문자, 숫자, '.', '_', '-'로 3~50자여야 한다");
		}
		requireMaxLength(name, MAX_NAME_LENGTH, "name");
		// 인코더가 만든 해시는 {bcrypt}처럼 알고리즘 이름으로 시작한다. 원문 비밀번호를 그대로 넘기는 실수를 여기서 막는다.
		if (!passwordHash.startsWith("{")) {
			throw new IllegalArgumentException("passwordHash는 해시된 값이어야 한다");
		}
		role.validateCampus(campusId);
		return new User(normalizedLoginId, passwordHash, name, role, campusId);
	}

	/**
	 * 아이디를 비교할 수 있는 형태로 맞춘다. 저장할 때와 찾을 때 같은 규칙을 써야 한다.
	 * DB는 대소문자를 구분하지 않고 Java는 구분하므로, 소문자로 통일해 둘의 판단이 어긋나지 않게 한다.
	 */
	public static String normalizeLoginId(String loginId) {
		return loginId.trim().toLowerCase(Locale.ROOT);
	}

	/**
	 * 맞춘 아이디가 허용하는 형식인지. 영문 소문자, 숫자, 점, 밑줄, 하이픈으로 3~50자다.
	 * DB는 악센트가 붙은 글자나 전각 문자를 같은 글자로 본다. 그런 문자를 허용하면 서로 다른 입력이 한 계정에 닿아서,
	 * 입력한 아이디 기준으로 로그인 시도를 세는 장치를 피할 수 있게 된다.
	 */
	public static boolean isValidLoginId(String normalizedLoginId) {
		return LOGIN_ID.matcher(normalizedLoginId).matches();
	}

	/**
	 * 다음 로그인부터 막는다. 이미 로그인된 세션은 이 메서드만으로는 끊기지 않는다.
	 * 계정을 차단할 때는 세션까지 끊는 {@code AccountService#disable}을 쓴다.
	 */
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

	private static void requireMaxLength(String value, int maxLength, String field) {
		if (value.length() > maxLength) {
			throw new IllegalArgumentException(field + "은(는) " + maxLength + "자를 넘을 수 없다");
		}
	}

}
